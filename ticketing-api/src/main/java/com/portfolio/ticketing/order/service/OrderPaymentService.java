package com.portfolio.ticketing.order.service;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.DuplicateResourceException;
import com.portfolio.ticketing.common.exception.InvalidStateTransitionException;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.order.domain.Order;
import com.portfolio.ticketing.order.domain.OrderStatus;
import com.portfolio.ticketing.order.repository.OrderRepository;
import com.portfolio.ticketing.payment.api.PaymentWebhookRequest;
import com.portfolio.ticketing.payment.domain.Payment;
import com.portfolio.ticketing.payment.domain.PaymentStatus;
import com.portfolio.ticketing.payment.service.PaymentService;
import com.portfolio.ticketing.reservation.service.ReservationConfirmationResult;
import com.portfolio.ticketing.reservation.service.ReservationService;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class OrderPaymentService {

  private final OrderRepository orderRepository;
  private final PaymentService paymentService;
  private final ReservationService reservationService;
  private final TicketService ticketService;

  public OrderPaymentService(
      OrderRepository orderRepository,
      PaymentService paymentService,
      ReservationService reservationService,
      TicketService ticketService) {
    this.orderRepository = orderRepository;
    this.paymentService = paymentService;
    this.reservationService = reservationService;
    this.ticketService = ticketService;
  }

  public void process(PaymentWebhookRequest request) {
    validate(request);
    Order order =
        orderRepository
            .findById(request.orderId())
            .orElseThrow(
                () -> new ResourceNotFoundException("Order " + request.orderId() + " not found"));
    Payment payment = paymentService.findRequiredByOrderId(order.getId());
    if (request.status() == PaymentStatus.SUCCEEDED) {
      processSuccess(order, payment, request.providerReference());
    } else {
      processFailure(order, payment);
    }
  }

  private void processSuccess(Order order, Payment payment, String providerReference) {
    if ((order.getStatus() == OrderStatus.PAID && payment.getStatus() == PaymentStatus.SUCCEEDED)
        || (order.getStatus() == OrderStatus.REFUNDED
            && payment.getStatus() == PaymentStatus.REFUNDED)) {
      requireSameProviderReference(payment, providerReference);
      return;
    }
    requirePending(order, payment);
    ReservationConfirmationResult confirmation =
        reservationService.confirmForPayment(order.getReservationId(), order.getUserId());
    if (confirmation == ReservationConfirmationResult.OUT_OF_STOCK) {
      payment.markSucceeded(providerReference);
      payment.refund();
      order.markPaid();
      order.refund();
    } else {
      order.markPaid();
      payment.markSucceeded(providerReference);
      ticketService.issueForOrder(order);
    }
    orderRepository.save(order);
    paymentService.save(payment);
  }

  private void processFailure(Order order, Payment payment) {
    if (order.getStatus() == OrderStatus.CANCELLED && payment.getStatus() == PaymentStatus.FAILED) {
      return;
    }
    requirePending(order, payment);
    reservationService.releaseForPayment(order.getReservationId(), order.getUserId());
    order.cancel();
    payment.markFailed();
    orderRepository.save(order);
    paymentService.save(payment);
  }

  private static void requirePending(Order order, Payment payment) {
    if (order.getStatus() != OrderStatus.PENDING_PAYMENT
        || payment.getStatus() != PaymentStatus.PENDING) {
      throw new InvalidStateTransitionException("Order or payment is not pending");
    }
  }

  private static void requireSameProviderReference(Payment payment, String providerReference) {
    if (!Objects.equals(payment.getProviderReference(), providerReference)) {
      throw new DuplicateResourceException("Payment was already confirmed with another reference");
    }
  }

  private static void validate(PaymentWebhookRequest request) {
    if (request == null || request.orderId() == null || request.status() == null) {
      throw new BusinessRuleViolationException("orderId and status are required");
    }
    if (request.status() != PaymentStatus.SUCCEEDED && request.status() != PaymentStatus.FAILED) {
      throw new BusinessRuleViolationException("Webhook status must be SUCCEEDED or FAILED");
    }
    if (request.status() == PaymentStatus.SUCCEEDED
        && (request.providerReference() == null || request.providerReference().isBlank())) {
      throw new BusinessRuleViolationException(
          "providerReference is required for a successful payment");
    }
  }
}
