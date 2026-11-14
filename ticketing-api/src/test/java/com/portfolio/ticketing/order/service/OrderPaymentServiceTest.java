package com.portfolio.ticketing.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.DuplicateResourceException;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.order.domain.FulfillmentStatus;
import com.portfolio.ticketing.order.domain.Order;
import com.portfolio.ticketing.order.domain.OrderStatus;
import com.portfolio.ticketing.order.domain.RefundStatus;
import com.portfolio.ticketing.order.repository.OrderRepository;
import com.portfolio.ticketing.payment.api.PaymentWebhookRequest;
import com.portfolio.ticketing.payment.domain.Payment;
import com.portfolio.ticketing.payment.domain.PaymentStatus;
import com.portfolio.ticketing.payment.domain.ProviderOperationKind;
import com.portfolio.ticketing.payment.service.PaymentService;
import com.portfolio.ticketing.reservation.service.ReservationConfirmationResult;
import com.portfolio.ticketing.reservation.service.ReservationService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderPaymentServiceTest {

  private static final UUID ORDER_ID = UUID.randomUUID();
  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID RESERVATION_ID = UUID.randomUUID();

  @Mock private OrderRepository orderRepository;
  @Mock private PaymentService paymentService;
  @Mock private ReservationService reservationService;
  @Mock private TicketService ticketService;
  @Mock private EntityManager entityManager;

  private OrderPaymentService orderPaymentService;

  @BeforeEach
  void setUp() {
    orderPaymentService =
        new OrderPaymentService(
            orderRepository, paymentService, reservationService, ticketService, entityManager);
  }

  @Test
  void should_confirmOrderAndIssueTickets_when_paymentSucceeds() {
    Order order = order();
    Payment payment = payment();
    stubOrderAndPayment(order, payment);
    stubLocks(payment);
    when(reservationService.confirmForPayment(RESERVATION_ID, USER_ID))
        .thenReturn(ReservationConfirmationResult.CONFIRMED);
    when(orderRepository.save(order)).thenReturn(order);

    orderPaymentService.process(successRequest());

    assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    verify(ticketService).issueForOrder(order);
    verify(orderRepository).save(order);
    verify(paymentService).save(payment);
  }

  @Test
  void should_beIdempotent_when_successfulWebhookIsRepeated() {
    Order order = order();
    order.markPaid();
    Payment payment = payment();
    payment.markSucceeded("provider-123");
    stubOrderAndPayment(order, payment);

    orderPaymentService.process(successRequest());

    verify(reservationService, never()).confirmForPayment(any(), any());
    verify(ticketService, never()).issueForOrder(any());
    verify(orderRepository, never()).save(any());
    verify(paymentService, never()).save(any());
  }

  @Test
  void should_ignoreCapture_when_refundWasAlreadyConfirmed() {
    Order order = order();
    order.markPaid();
    order.markFulfilled();
    order.requestRefund();
    order.confirmRefund();
    Payment payment = payment();
    payment.markSucceeded("provider-123");
    payment.refund("refund-123");
    stubOrderAndPayment(order, payment);

    orderPaymentService.process(successRequest());

    verify(reservationService, never()).confirmForPayment(any(), any());
    verify(ticketService, never()).issueForOrder(any());
    verify(orderRepository, never()).save(any());
    verify(paymentService, never()).save(any());
  }

  @Test
  void should_rejectRepeatedSuccess_when_providerReferenceDiffers() {
    Order order = order();
    order.markPaid();
    Payment payment = payment();
    payment.markSucceeded("provider-123");
    stubOrderAndPayment(order, payment);

    assertThatThrownBy(() -> orderPaymentService.process(successRequest("other-reference")))
        .isInstanceOf(DuplicateResourceException.class);
  }

  @Test
  void should_requireRefundWithoutConfirmingIt_when_expiredStockCannotBeRecovered() {
    Order order = order();
    Payment payment = payment();
    stubOrderAndPayment(order, payment);
    stubLocks();
    when(reservationService.confirmForPayment(RESERVATION_ID, USER_ID))
        .thenReturn(ReservationConfirmationResult.OUT_OF_STOCK);

    orderPaymentService.process(successRequest());

    assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    assertThat(order.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.UNFULFILLABLE);
    assertThat(order.getRefundStatus()).isEqualTo(RefundStatus.REQUIRED);
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    verify(ticketService, never()).issueForOrder(any());
    verify(orderRepository).save(order);
    verify(paymentService).save(payment);
  }

  @Test
  void should_cancelOrderAndReleaseReservation_when_paymentFails() {
    Order order = order();
    Payment payment = payment();
    stubOrderAndPayment(order, payment);
    stubLocks();

    orderPaymentService.process(failureRequest());

    assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
    verify(reservationService).releaseForPayment(RESERVATION_ID, USER_ID);
  }

  @Test
  void should_beIdempotent_when_failedWebhookIsRepeated() {
    Order order = order();
    order.cancel();
    Payment payment = payment();
    payment.markFailed();
    stubOrderAndPayment(order, payment);
    stubLocks();

    orderPaymentService.process(failureRequest());

    verify(reservationService, never()).releaseForPayment(any(), any());
    verify(orderRepository, never()).save(any());
  }

  @Test
  void should_rejectUnsupportedWebhookStatus() {
    assertThatThrownBy(() ->
            orderPaymentService.process(
                request(PaymentStatus.PENDING, ProviderOperationKind.PAYMENT_CAPTURE, null)))
        .isInstanceOf(BusinessRuleViolationException.class);
    verifyNoInteractions(orderRepository, paymentService, reservationService, ticketService);
  }

  @Test
  void should_keepCancelledOrderAndRequireRefund_when_lateCaptureArrives() {
    Order order = order();
    order.cancel();
    Payment payment = payment();
    stubOrderAndPayment(order, payment);
    stubLocks();

    orderPaymentService.process(successRequest());

    assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    assertThat(order.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.UNFULFILLABLE);
    assertThat(order.getRefundStatus()).isEqualTo(RefundStatus.REQUIRED);
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    verify(reservationService, never()).confirmForPayment(any(), any());
    verify(ticketService, never()).issueForOrder(any());
  }

  @Test
  void should_confirmRefundOnlyWithProviderEvidence() {
    Order order = order();
    order.markPaid();
    order.markFulfilled();
    order.requestRefund();
    Payment payment = payment();
    payment.markSucceeded("provider-123");
    stubOrderAndPayment(order, payment);
    stubLocks();

    orderPaymentService.process(
        request(PaymentStatus.REFUNDED, ProviderOperationKind.PAYMENT_REFUND, "refund-123"));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
    assertThat(order.getRefundStatus()).isEqualTo(RefundStatus.CONFIRMED);
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    assertThat(payment.getRefundProviderReference()).isEqualTo("refund-123");
    verify(ticketService).cancelForOrder(ORDER_ID);
  }

  @Test
  void should_rejectCapture_when_amountDoesNotMatch() {
    Order order = order();
    Payment payment = payment();
    stubOrderAndPayment(order, payment);
    PaymentWebhookRequest request =
        new PaymentWebhookRequest(
            ORDER_ID,
            PaymentStatus.SUCCEEDED,
            "provider-123",
            UUID.randomUUID(),
            "operation-amount",
            ProviderOperationKind.PAYMENT_CAPTURE,
            new BigDecimal("81.00"),
            "USD");

    assertThatThrownBy(() -> orderPaymentService.process(request))
        .isInstanceOf(BusinessRuleViolationException.class);
    verify(reservationService, never()).confirmForPayment(any(), any());
  }

  @Test
  void should_rejectCapture_when_currencyDoesNotMatch() {
    Order order = order();
    Payment payment = payment();
    stubOrderAndPayment(order, payment);
    PaymentWebhookRequest request =
        new PaymentWebhookRequest(
            ORDER_ID,
            PaymentStatus.SUCCEEDED,
            "provider-123",
            UUID.randomUUID(),
            "operation-currency",
            ProviderOperationKind.PAYMENT_CAPTURE,
            new BigDecimal("80.00"),
            "EUR");

    assertThatThrownBy(() -> orderPaymentService.process(request))
        .isInstanceOf(BusinessRuleViolationException.class);
    verify(reservationService, never()).confirmForPayment(any(), any());
  }

  @Test
  void should_returnNotFound_when_orderDoesNotExist() {
    when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> orderPaymentService.process(successRequest()))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  private void stubOrderAndPayment(Order order, Payment payment) {
    when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
    when(paymentService.findRequiredByOrderId(ORDER_ID)).thenReturn(payment);
  }

  private void stubLocks(Payment payment) {
    when(orderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(ORDER_ID));
    when(paymentService.lockRequiredByOrderId(ORDER_ID)).thenReturn(payment);
  }

  private static PaymentWebhookRequest successRequest() {
    return successRequest("provider-123");
  }

  private static PaymentWebhookRequest successRequest(String providerReference) {
    return request(
        PaymentStatus.SUCCEEDED, ProviderOperationKind.PAYMENT_CAPTURE, providerReference);
  }

  private static PaymentWebhookRequest failureRequest() {
    return request(PaymentStatus.FAILED, ProviderOperationKind.PAYMENT_FAILURE, null);
  }

  private static PaymentWebhookRequest request(
      PaymentStatus status, ProviderOperationKind operationKind, String providerReference) {
    return new PaymentWebhookRequest(
        ORDER_ID,
        status,
        providerReference,
        UUID.randomUUID(),
        "operation-" + UUID.randomUUID(),
        operationKind,
        new BigDecimal("80.00"),
        "USD");
  }

  private static Order order() {
    Order order = new Order(USER_ID, RESERVATION_ID, "key");
    order.addItem(UUID.randomUUID(), 2, new BigDecimal("40.00"));
    ReflectionTestUtils.setField(order, "id", ORDER_ID);
    return order;
  }

  private static Payment payment() {
    return new Payment(ORDER_ID, new BigDecimal("80.00"));
  }
}
