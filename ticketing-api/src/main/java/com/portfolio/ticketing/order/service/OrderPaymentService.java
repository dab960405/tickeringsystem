package com.portfolio.ticketing.order.service;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.DuplicateResourceException;
import com.portfolio.ticketing.common.exception.InvalidStateTransitionException;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.order.domain.Order;
import com.portfolio.ticketing.order.domain.OrderStatus;
import com.portfolio.ticketing.order.domain.RefundStatus;
import com.portfolio.ticketing.order.repository.OrderRepository;
import com.portfolio.ticketing.payment.api.PaymentWebhookRequest;
import com.portfolio.ticketing.payment.domain.Payment;
import com.portfolio.ticketing.payment.domain.PaymentStatus;
import com.portfolio.ticketing.payment.service.PaymentService;
import com.portfolio.ticketing.payment.service.PaymentWebhookRequestValidator;
import com.portfolio.ticketing.reservation.service.ReservationConfirmationResult;
import com.portfolio.ticketing.reservation.service.ReservationService;
import jakarta.persistence.EntityManager;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class OrderPaymentService {

  private final OrderRepository orderRepository;
  private final PaymentService paymentService;
  private final ReservationService reservationService;
  private final TicketService ticketService;
  private final EntityManager entityManager;

  public OrderPaymentService(
      OrderRepository orderRepository,
      PaymentService paymentService,
      ReservationService reservationService,
      TicketService ticketService,
      EntityManager entityManager) {
    this.orderRepository = orderRepository;
    this.paymentService = paymentService;
    this.reservationService = reservationService;
    this.ticketService = ticketService;
    this.entityManager = entityManager;
  }

  public void process(PaymentWebhookRequest request) {
    PaymentWebhookRequestValidator.validate(request);
    Order order =
        orderRepository
            .findById(request.orderId())
            .orElseThrow(
                () -> new ResourceNotFoundException("Order " + request.orderId() + " not found"));
    Payment payment = paymentService.findRequiredByOrderId(order.getId());
    validateFinancialEvidence(order, payment, request);
    switch (request.operationKind()) {
      case PAYMENT_CAPTURE -> processSuccess(order, payment, request);
      case PAYMENT_FAILURE -> processFailure(order, payment);
      case PAYMENT_REFUND -> processRefund(order, payment, request);
    }
  }

  private void processSuccess(Order order, Payment payment, PaymentWebhookRequest request) {
    if (payment.getStatus() == PaymentStatus.REFUNDED) {
      return;
    }
    if (order.getStatus() == OrderStatus.PAID && payment.getStatus() == PaymentStatus.SUCCEEDED) {
      requireSameProviderReference(payment, request.providerReference());
      return;
    }
    UUID orderId = order.getId();
    ReservationConfirmationResult confirmation =
        order.getStatus() == OrderStatus.CANCELLED
            ? ReservationConfirmationResult.RECOVERY_NOT_ALLOWED
            : reservationService.confirmForPayment(order.getReservationId(), order.getUserId());
    LockedOrderPayment locked = lockOrderAndPayment(orderId);
    order = locked.order();
    payment = locked.payment();

    if (payment.getStatus() == PaymentStatus.REFUNDED) {
      return;
    }
    if (order.getStatus() == OrderStatus.PAID && payment.getStatus() == PaymentStatus.SUCCEEDED) {
      requireSameProviderReference(payment, request.providerReference());
      return;
    }
    if (payment.getStatus() != PaymentStatus.PENDING
        || (order.getStatus() != OrderStatus.PENDING_PAYMENT
            && order.getStatus() != OrderStatus.CANCELLED)) {
      throw new InvalidStateTransitionException("Order or payment cannot accept this capture");
    }

    payment.markSucceeded(request.providerReference());
    if (confirmation == ReservationConfirmationResult.CONFIRMED
        && order.getStatus() == OrderStatus.PENDING_PAYMENT) {
      order.markPaid();
      orderRepository.save(order);
      ticketService.issueForOrder(order);
      order.markFulfilled();
    } else {
      if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
        order.markPaid();
      }
      order.markUnfulfillable();
      orderRepository.save(order);
    }
    paymentService.save(payment);
  }

  private void processFailure(Order order, Payment payment) {
    if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
      reservationService.releaseForPayment(order.getReservationId(), order.getUserId());
    }
    LockedOrderPayment locked = lockOrderAndPayment(order.getId());
    order = locked.order();
    payment = locked.payment();

    if (payment.getStatus() == PaymentStatus.SUCCEEDED
        || payment.getStatus() == PaymentStatus.REFUNDED) {
      return;
    }
    if (order.getStatus() == OrderStatus.CANCELLED && payment.getStatus() == PaymentStatus.FAILED) {
      return;
    }
    if (order.getStatus() == OrderStatus.CANCELLED && payment.getStatus() == PaymentStatus.PENDING) {
      payment.markFailed();
      paymentService.save(payment);
      return;
    }
    requirePending(order, payment);
    order.cancel();
    payment.markFailed();
    orderRepository.save(order);
    paymentService.save(payment);
  }

  private void processRefund(Order order, Payment payment, PaymentWebhookRequest request) {
    LockedOrderPayment locked = lockOrderAndPayment(order.getId());
    order = locked.order();
    payment = locked.payment();
    switch (request.status()) {
      case REFUNDED -> confirmRefund(order, payment, request);
      case FAILED -> recordRefundFailure(order, payment);
      case PENDING -> recordRefundPending(order, payment);
      case SUCCEEDED ->
          throw new BusinessRuleViolationException("A refund cannot have SUCCEEDED status");
    }
  }

  private void confirmRefund(Order order, Payment payment, PaymentWebhookRequest request) {
    if (payment.getStatus() == PaymentStatus.REFUNDED) {
      if (!Objects.equals(payment.getRefundProviderReference(), request.providerReference())) {
        throw new DuplicateResourceException("Refund was already confirmed with another reference");
      }
      return;
    }
    if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
      return;
    }
    if (order.getStatus() != OrderStatus.PAID && order.getStatus() != OrderStatus.CANCELLED) {
      throw new InvalidStateTransitionException("Order cannot accept refund evidence");
    }
    payment.refund(request.providerReference());
    order.confirmRefund();
    ticketService.cancelForOrder(order.getId());
    orderRepository.save(order);
    paymentService.save(payment);
  }

  private void recordRefundFailure(Order order, Payment payment) {
    if (payment.getStatus() != PaymentStatus.SUCCEEDED
        || order.getRefundStatus() == RefundStatus.NONE) {
      return;
    }
    order.markRefundFailed();
    orderRepository.save(order);
  }

  private void recordRefundPending(Order order, Payment payment) {
    if (payment.getStatus() != PaymentStatus.SUCCEEDED
        || order.getRefundStatus() == RefundStatus.NONE) {
      return;
    }
    order.markRefundPendingReconciliation();
    orderRepository.save(order);
  }

  /**
   * Acquires O → P after any reservation-side E → T → R locks and refreshes state read before
   * waiting. This prevents a stale webhook entity from overwriting a concurrent cancellation.
   */
  private LockedOrderPayment lockOrderAndPayment(UUID orderId) {
    orderRepository
        .lockById(orderId)
        .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " not found"));
    Order order =
        orderRepository
            .findById(orderId)
            .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " not found"));
    entityManager.refresh(order);
    Payment payment = paymentService.lockRequiredByOrderId(orderId);
    entityManager.refresh(payment);
    return new LockedOrderPayment(order, payment);
  }

  private record LockedOrderPayment(Order order, Payment payment) {}

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

  private static void validateFinancialEvidence(
      Order order, Payment payment, PaymentWebhookRequest request) {
    if (request.amount().compareTo(order.getTotalAmount()) != 0
        || request.amount().compareTo(payment.getAmount()) != 0) {
      throw new BusinessRuleViolationException("Webhook amount does not match the order");
    }
    if (!request.currency().equals(order.getCurrency())
        || !request.currency().equals(payment.getCurrency())) {
      throw new BusinessRuleViolationException("Webhook currency does not match the order");
    }
  }
}
