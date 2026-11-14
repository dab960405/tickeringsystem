package com.portfolio.ticketing.event.service;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.domain.EventStatus;
import com.portfolio.ticketing.event.repository.EventRepository;
import com.portfolio.ticketing.order.domain.Order;
import com.portfolio.ticketing.order.domain.OrderStatus;
import com.portfolio.ticketing.order.repository.OrderRepository;
import com.portfolio.ticketing.reservation.domain.Reservation;
import com.portfolio.ticketing.reservation.domain.ReservationInventoryDisposition;
import com.portfolio.ticketing.reservation.domain.ReservationStatus;
import com.portfolio.ticketing.reservation.repository.ReservationRepository;
import com.portfolio.ticketing.tickettype.service.TicketTypeInventoryService;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cancels an event and resolves every still-held reservation in the same transaction.
 *
 * <p>The event lock excludes new reservations and payment confirmation. Resource locks then
 * follow ADR-016: T (ticket types), R (reservations), and O (orders). Payments are deliberately
 * left pending: cancellation is not evidence that a provider payment failed or was refunded.
 */
@Service
@Transactional
public class EventCancellationService {

  private final EventRepository eventRepository;
  private final EventLockService eventLockService;
  private final TicketTypeInventoryService inventoryService;
  private final ReservationRepository reservationRepository;
  private final OrderRepository orderRepository;
  private final JdbcTemplate jdbcTemplate;

  public EventCancellationService(
      EventRepository eventRepository,
      EventLockService eventLockService,
      TicketTypeInventoryService inventoryService,
      ReservationRepository reservationRepository,
      OrderRepository orderRepository,
      JdbcTemplate jdbcTemplate) {
    this.eventRepository = eventRepository;
    this.eventLockService = eventLockService;
    this.inventoryService = inventoryService;
    this.reservationRepository = reservationRepository;
    this.orderRepository = orderRepository;
    this.jdbcTemplate = jdbcTemplate;
  }

  public void cancel(UUID eventId) {
    EventStatus lockedStatus = eventLockService.lockExclusive(eventId);
    Event event =
        eventRepository
            .findById(eventId)
            .orElseThrow(
                () -> new com.portfolio.ticketing.common.exception.ResourceNotFoundException(
                    "Event " + eventId + " not found"));

    if (lockedStatus != EventStatus.CANCELLED) {
      event.cancel();
      eventRepository.saveAndFlush(event);
    }

    List<UUID> ticketTypeIds =
        jdbcTemplate.query(
            "SELECT id FROM ticket_types WHERE event_id = ? ORDER BY id",
            (row, index) -> row.getObject(1, UUID.class),
            eventId);
    inventoryService.lockAll(ticketTypeIds);
    if (ticketTypeIds.isEmpty()) {
      return;
    }

    List<ReservationCandidate> reservations =
        jdbcTemplate.query(
            """
            SELECT r.id, r.ticket_type_id, r.quantity
            FROM reservations r
            JOIN ticket_types t ON t.id = r.ticket_type_id
            WHERE t.event_id = ?
              AND r.status = 'ACTIVE'
              AND r.inventory_disposition = 'HELD'
            ORDER BY r.id
            FOR UPDATE OF r
            """,
            (row, index) ->
                new ReservationCandidate(
                    row.getObject("id", UUID.class),
                    row.getObject("ticket_type_id", UUID.class),
                    row.getInt("quantity")),
            eventId);

    if (reservations.isEmpty()) {
      return;
    }

    List<UUID> reservationIds = reservations.stream().map(ReservationCandidate::id).toList();
    List<Reservation> lockedReservations = reservationRepository.findAllById(reservationIds);
    if (lockedReservations.size() != reservations.size()) {
      throw new BusinessRuleViolationException(
          "One or more event reservations disappeared during cancellation");
    }

    for (Reservation reservation : lockedReservations) {
      if (reservation.getStatus() != ReservationStatus.ACTIVE
          || reservation.getInventoryDisposition() != ReservationInventoryDisposition.HELD) {
        throw new BusinessRuleViolationException(
            "Reservation changed while its event was being cancelled");
      }
      reservation.expire();
    }
    reservationRepository.saveAllAndFlush(lockedReservations);

    for (ReservationCandidate candidate : reservations) {
      inventoryService.release(candidate.ticketTypeId(), candidate.quantity());
    }

    String placeholders = String.join(",", Collections.nCopies(reservationIds.size(), "?"));
    List<UUID> pendingOrderIds =
        jdbcTemplate.query(
            "SELECT id FROM orders WHERE reservation_id IN ("
                + placeholders
                + ") AND status = 'PENDING_PAYMENT' ORDER BY id FOR UPDATE",
            (row, index) -> row.getObject(1, UUID.class),
            reservationIds.toArray());
    if (pendingOrderIds.isEmpty()) {
      return;
    }

    List<Order> pendingOrders = orderRepository.findAllById(pendingOrderIds);
    if (pendingOrders.size() != pendingOrderIds.size()) {
      throw new BusinessRuleViolationException(
          "One or more pending orders disappeared during event cancellation");
    }
    for (Order order : pendingOrders) {
      if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
        throw new BusinessRuleViolationException(
            "Order changed while its event was being cancelled");
      }
      order.cancel();
    }
    orderRepository.saveAll(pendingOrders);
  }

  private record ReservationCandidate(UUID id, UUID ticketTypeId, int quantity) {}
}
