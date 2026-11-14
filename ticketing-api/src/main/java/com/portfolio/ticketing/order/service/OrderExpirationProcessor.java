package com.portfolio.ticketing.order.service;

import com.portfolio.ticketing.common.exception.InvalidStateTransitionException;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.event.service.EventLockService;
import com.portfolio.ticketing.order.domain.Order;
import com.portfolio.ticketing.order.domain.OrderStatus;
import com.portfolio.ticketing.order.repository.OrderExpirationCandidate;
import com.portfolio.ticketing.order.repository.OrderRepository;
import com.portfolio.ticketing.reservation.domain.Reservation;
import com.portfolio.ticketing.reservation.domain.ReservationInventoryDisposition;
import com.portfolio.ticketing.reservation.domain.ReservationStatus;
import com.portfolio.ticketing.reservation.repository.ReservationRepository;
import com.portfolio.ticketing.tickettype.service.TicketTypeInventoryService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderExpirationProcessor {

  private final EventLockService eventLockService;
  private final TicketTypeInventoryService inventoryService;
  private final ReservationRepository reservationRepository;
  private final OrderRepository orderRepository;
  private final EntityManager entityManager;

  public OrderExpirationProcessor(
      EventLockService eventLockService,
      TicketTypeInventoryService inventoryService,
      ReservationRepository reservationRepository,
      OrderRepository orderRepository,
      EntityManager entityManager) {
    this.eventLockService = eventLockService;
    this.inventoryService = inventoryService;
    this.reservationRepository = reservationRepository;
    this.orderRepository = orderRepository;
    this.entityManager = entityManager;
  }

  /**
   * Processes one candidate in its own transaction, respecting ADR-016 lock order E → T → R → O.
   */
  @Transactional
  public void expire(OrderExpirationCandidate candidate) {
    eventLockService.lockShared(candidate.getEventId());
    inventoryService.lockRequired(candidate.getTicketTypeId());

    Reservation reservation =
        reservationRepository
            .findByIdForUpdate(candidate.getReservationId())
            .orElseThrow(
                () ->
                    new ResourceNotFoundException(
                        "Reservation " + candidate.getReservationId() + " not found"));
    entityManager.refresh(reservation);
    if (!reservation.getTicketType().getId().equals(candidate.getTicketTypeId())) {
      throw new InvalidStateTransitionException("Reservation inventory relationship changed");
    }

    Instant evaluationTime = reservationRepository.databaseNow();
    if (reservation.getStatus() == ReservationStatus.ACTIVE
        && reservation.getInventoryDisposition() == ReservationInventoryDisposition.HELD) {
      if (reservation.getExpiresAt().isAfter(evaluationTime)) {
        return;
      }
      if (reservationRepository.expireActive(reservation.getId(), evaluationTime) != 1) {
        return;
      }
      inventoryService.release(candidate.getTicketTypeId(), reservation.getQuantity());
    } else if (reservation.getStatus() == ReservationStatus.CANCELLED
        && reservation.getInventoryDisposition() == ReservationInventoryDisposition.RELEASED) {
      return;
    } else if (reservation.getStatus() == ReservationStatus.CONFIRMED
        && reservation.getInventoryDisposition() == ReservationInventoryDisposition.CONSUMED) {
      return;
    } else if (reservation.getStatus() != ReservationStatus.EXPIRED
        || reservation.getInventoryDisposition() != ReservationInventoryDisposition.RELEASED) {
      throw new InvalidStateTransitionException(
          "Reservation status and inventory disposition are inconsistent");
    }

    orderRepository
        .lockById(candidate.getOrderId())
        .orElseThrow(
            () -> new ResourceNotFoundException("Order " + candidate.getOrderId() + " not found"));
    Order order =
        orderRepository
            .findById(candidate.getOrderId())
            .orElseThrow(
                () -> new ResourceNotFoundException("Order " + candidate.getOrderId() + " not found"));
    entityManager.refresh(order);
    if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
      return;
    }

    // No payment failure is inferred from the absence of a webhook. A later provider
    // capture must still be recorded and reconciled as unfulfillable/refundable.
    order.expire();
    orderRepository.save(order);
  }
}
