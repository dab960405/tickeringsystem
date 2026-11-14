package com.portfolio.ticketing.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.InvalidStateTransitionException;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.order.api.OrderResponse;
import com.portfolio.ticketing.order.domain.Order;
import com.portfolio.ticketing.order.domain.OrderStatus;
import com.portfolio.ticketing.order.domain.RefundStatus;
import com.portfolio.ticketing.order.repository.OrderRepository;
import com.portfolio.ticketing.payment.domain.Payment;
import com.portfolio.ticketing.payment.domain.PaymentStatus;
import com.portfolio.ticketing.payment.service.PaymentService;
import com.portfolio.ticketing.reservation.domain.Reservation;
import com.portfolio.ticketing.reservation.service.ReservationReleaseResult;
import com.portfolio.ticketing.reservation.service.ReservationService;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import jakarta.persistence.EntityManager;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderCancellationServiceTest {

  private static final Instant NOW = Instant.parse("2099-01-01T00:00:00Z");
  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID ORDER_ID = UUID.randomUUID();
  private static final UUID RESERVATION_ID = UUID.randomUUID();

  @Mock private OrderRepository orderRepository;
  @Mock private ReservationService reservationService;
  @Mock private PaymentService paymentService;
  @Mock private TicketService ticketService;
  @Mock private Reservation reservation;
  @Mock private TicketType ticketType;
  @Mock private Event event;
  @Mock private EntityManager entityManager;

  private OrderCancellationService cancellationService;

  @BeforeEach
  void setUp() {
    cancellationService =
        new OrderCancellationService(
            orderRepository, reservationService, Clock.fixed(NOW, ZoneOffset.UTC), entityManager);
  }

  @Test
  void should_releaseReservationAndCancelOrder_when_paymentIsPending() {
    Order order = order(OrderStatus.PENDING_PAYMENT);
    when(orderRepository.findByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.of(order));
    when(orderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(ORDER_ID));
    when(reservationService.releaseForPayment(RESERVATION_ID, USER_ID))
        .thenReturn(ReservationReleaseResult.RELEASED);
    when(orderRepository.save(order)).thenReturn(order);

    OrderResponse response = cancellationService.cancel(ORDER_ID, USER_ID);

    assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
    verify(reservationService).releaseForPayment(RESERVATION_ID, USER_ID);
    verifyNoInteractions(paymentService, ticketService);
  }

  @Test
  void should_rejectCancellation_when_reservationWasAlreadyConfirmed() {
    Order order = order(OrderStatus.PENDING_PAYMENT);
    when(orderRepository.findByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.of(order));
    when(reservationService.releaseForPayment(RESERVATION_ID, USER_ID))
        .thenReturn(ReservationReleaseResult.CONFIRMED);

    assertThatThrownBy(() -> cancellationService.cancel(ORDER_ID, USER_ID))
        .isInstanceOf(InvalidStateTransitionException.class);

    verify(orderRepository, never()).lockById(ORDER_ID);
    verify(orderRepository, never()).save(order);
  }

  @Test
  void should_requestRefundWithoutExecutingIt_when_eventHasNotStarted() {
    Order order = order(OrderStatus.PAID);
    when(orderRepository.findByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.of(order));
    when(orderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(ORDER_ID));
    when(orderRepository.save(order)).thenReturn(order);
    when(reservationService.findForOrder(RESERVATION_ID, USER_ID)).thenReturn(reservation);
    when(reservation.getTicketType()).thenReturn(ticketType);
    when(ticketType.getEvent()).thenReturn(event);
    when(event.getStartsAt()).thenReturn(NOW.plusSeconds(3600));

    OrderResponse response = cancellationService.cancel(ORDER_ID, USER_ID);

    assertThat(response.status()).isEqualTo(OrderStatus.PAID);
    assertThat(response.refundStatus()).isEqualTo(RefundStatus.REQUESTED);
    verifyNoInteractions(paymentService, ticketService);
  }

  @Test
  void should_rejectRefund_when_eventHasAlreadyStarted() {
    Order order = order(OrderStatus.PAID);
    when(orderRepository.findByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.of(order));
    when(orderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(ORDER_ID));
    when(reservationService.findForOrder(RESERVATION_ID, USER_ID)).thenReturn(reservation);
    when(reservation.getTicketType()).thenReturn(ticketType);
    when(ticketType.getEvent()).thenReturn(event);
    when(event.getStartsAt()).thenReturn(NOW);

    assertThatThrownBy(() -> cancellationService.cancel(ORDER_ID, USER_ID))
        .isInstanceOf(BusinessRuleViolationException.class);

    verifyNoInteractions(paymentService, ticketService);
    verify(orderRepository, never()).save(order);
  }

  @Test
  void should_rejectCancellation_when_orderIsAlreadyFinal() {
    Order order = order(OrderStatus.CANCELLED);
    when(orderRepository.findByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.of(order));
    when(orderRepository.lockById(ORDER_ID)).thenReturn(Optional.of(ORDER_ID));

    assertThatThrownBy(() -> cancellationService.cancel(ORDER_ID, USER_ID))
        .isInstanceOf(InvalidStateTransitionException.class);

    verifyNoInteractions(reservationService, paymentService, ticketService);
  }

  @Test
  void should_returnNotFound_when_orderBelongsToAnotherUser() {
    when(orderRepository.findByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> cancellationService.cancel(ORDER_ID, USER_ID))
        .isInstanceOf(ResourceNotFoundException.class);

    verifyNoInteractions(reservationService, paymentService, ticketService);
  }

  private static Order order(OrderStatus status) {
    Order order = new Order(USER_ID, RESERVATION_ID, "checkout-123");
    order.addItem(UUID.randomUUID(), 1, new BigDecimal("40.00"));
    ReflectionTestUtils.setField(order, "id", ORDER_ID);
    if (status == OrderStatus.PAID) {
      order.markPaid();
      order.markFulfilled();
    } else if (status == OrderStatus.CANCELLED) {
      order.cancel();
    }
    return order;
  }

  private static Payment payment(PaymentStatus status) {
    Payment payment = new Payment(ORDER_ID, new BigDecimal("40.00"));
    if (status == PaymentStatus.SUCCEEDED) {
      payment.markSucceeded("provider-reference");
    }
    return payment;
  }
}
