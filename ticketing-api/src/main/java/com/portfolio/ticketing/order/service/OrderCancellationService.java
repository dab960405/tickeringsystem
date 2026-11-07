package com.portfolio.ticketing.order.service;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.InvalidStateTransitionException;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.order.api.OrderResponse;
import com.portfolio.ticketing.order.domain.Order;
import com.portfolio.ticketing.order.repository.OrderRepository;
import com.portfolio.ticketing.payment.domain.Payment;
import com.portfolio.ticketing.payment.service.PaymentService;
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
  private final PaymentService paymentService;
  private final TicketService ticketService;
  private final Clock clock;

  public OrderCancellationService(
      OrderRepository orderRepository,
      ReservationService reservationService,
      PaymentService paymentService,
      TicketService ticketService,
      Clock clock) {
    this.orderRepository = orderRepository;
    this.reservationService = reservationService;
    this.paymentService = paymentService;
    this.ticketService = ticketService;
    this.clock = clock;
  }

  @PreAuthorize("hasRole('USER')")
  public OrderResponse cancel(UUID id, UUID userId) {
    Order order =
        orderRepository
            .findByIdAndUserId(id, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Order " + id + " not found"));
    return switch (order.getStatus()) {
      case PENDING_PAYMENT -> cancelPending(order, userId);
      case PAID -> refundPaid(order, userId);
      case CANCELLED, EXPIRED, REFUNDED ->
          throw new InvalidStateTransitionException(
              "Order cannot be cancelled from " + order.getStatus());
    };
  }

  private OrderResponse cancelPending(Order order, UUID userId) {
    reservationService.releaseForPayment(order.getReservationId(), userId);
    order.cancel();
    return OrderResponse.from(orderRepository.save(order));
  }

  private OrderResponse refundPaid(Order order, UUID userId) {
    Reservation reservation = reservationService.findForOrder(order.getReservationId(), userId);
    Instant eventStartsAt = reservation.getTicketType().getEvent().getStartsAt();
    if (!eventStartsAt.isAfter(clock.instant())) {
      throw new BusinessRuleViolationException(
          "A paid order cannot be refunded after the event has started");
    }
    Payment payment = paymentService.findRequiredByOrderId(order.getId());
    ticketService.cancelForOrder(order.getId());
    payment.refund();
    order.refund();
    paymentService.save(payment);
    return OrderResponse.from(orderRepository.save(order));
  }
}
