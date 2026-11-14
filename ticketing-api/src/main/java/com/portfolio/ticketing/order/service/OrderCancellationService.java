package com.portfolio.ticketing.order.service;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.InvalidStateTransitionException;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import com.portfolio.ticketing.order.api.OrderResponse;
import com.portfolio.ticketing.order.domain.Order;
import com.portfolio.ticketing.order.repository.OrderRepository;
import com.portfolio.ticketing.reservation.domain.Reservation;
import com.portfolio.ticketing.reservation.service.ReservationService;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class OrderCancellationService {

  private final OrderRepository orderRepository;
  private final ReservationService reservationService;
  private final Clock clock;
  private final EntityManager entityManager;

  public OrderCancellationService(
      OrderRepository orderRepository,
      ReservationService reservationService,
      Clock clock,
      EntityManager entityManager) {
    this.orderRepository = orderRepository;
    this.reservationService = reservationService;
    this.clock = clock;
    this.entityManager = entityManager;
  }

  @PreAuthorize("hasRole('USER')")
  public OrderResponse cancel(UUID id, UUID userId) {
    Order order =
        orderRepository
            .findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Order " + id + " not found"));
    if (order.getStatus() == com.portfolio.ticketing.order.domain.OrderStatus.PENDING_PAYMENT) {
      reservationService.releaseForPayment(order.getReservationId(), userId);
    }
    order = lockOrder(id, userId);
    return switch (order.getStatus()) {
      case PENDING_PAYMENT -> cancelPending(order);
      case PAID -> refundPaid(order, userId);
      case CANCELLED, EXPIRED, REFUNDED ->
          throw new InvalidStateTransitionException(
              "Order cannot be cancelled from " + order.getStatus());
    };
  }

  private OrderResponse cancelPending(Order order) {
    order.cancel();
    return OrderResponse.from(orderRepository.save(order));
  }

  private Order lockOrder(UUID id, UUID userId) {
    orderRepository
        .lockById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Order " + id + " not found"));
    Order locked =
        orderRepository
            .findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Order " + id + " not found"));
    entityManager.refresh(locked);
    return locked;
  }

  private OrderResponse refundPaid(Order order, UUID userId) {
    Reservation reservation = reservationService.findForOrder(order.getReservationId(), userId);
    Instant eventStartsAt = reservation.getTicketType().getEvent().getStartsAt();
    if (!eventStartsAt.isAfter(clock.instant())) {
      throw new BusinessRuleViolationException(
          "A paid order cannot be refunded after the event has started");
    }
    order.requestRefund();
    return OrderResponse.from(orderRepository.save(order));
  }
}
