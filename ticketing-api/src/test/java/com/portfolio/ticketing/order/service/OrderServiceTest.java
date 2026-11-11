package com.portfolio.ticketing.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.ticketing.common.api.PageResponse;
import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.DuplicateResourceException;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.order.api.OrderRequest;
import com.portfolio.ticketing.order.api.OrderResponse;
import com.portfolio.ticketing.order.domain.Order;
import com.portfolio.ticketing.order.repository.OrderRepository;
import com.portfolio.ticketing.payment.service.PaymentService;
import com.portfolio.ticketing.reservation.domain.Reservation;
import com.portfolio.ticketing.reservation.service.ReservationService;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID ORDER_ID = UUID.randomUUID();
  private static final UUID RESERVATION_ID = UUID.randomUUID();
  private static final UUID TICKET_TYPE_ID = UUID.randomUUID();

  @Mock private OrderRepository orderRepository;
  @Mock private ReservationService reservationService;
  @Mock private PaymentService paymentService;

  private OrderService orderService;

  @BeforeEach
  void setUp() {
    orderService = new OrderService(orderRepository, reservationService, paymentService);
  }

  @Test
  void should_createOrderWithCopiedPriceAndPendingPayment() {
    Reservation reservation = reservation();
    when(reservationService.findActiveForUser(RESERVATION_ID, USER_ID)).thenReturn(reservation);
    when(orderRepository.save(any(Order.class)))
        .thenAnswer(invocation -> {
          Order order = invocation.getArgument(0);
          ReflectionTestUtils.setField(order, "id", ORDER_ID);
          return order;
        });

    OrderCreationResult result =
        orderService.create(USER_ID, "checkout-123", new OrderRequest(RESERVATION_ID));

    assertThat(result.created()).isTrue();
    assertThat(result.response().id()).isEqualTo(ORDER_ID);
    assertThat(result.response().totalAmount()).isEqualByComparingTo("80.00");
    assertThat(result.response().items()).hasSize(1);
    assertThat(result.response().items().getFirst().unitPrice()).isEqualByComparingTo("40.00");
    verify(paymentService).createPending(eq(ORDER_ID), eq(new BigDecimal("80.00")));
  }

  @Test
  void should_returnExistingOrder_when_keyAndReservationMatch() {
    Order existing = existingOrder();
    when(orderRepository.findByUserIdAndIdempotencyKey(USER_ID, "checkout-123"))
        .thenReturn(Optional.of(existing));

    OrderCreationResult result =
        orderService.create(USER_ID, "checkout-123", new OrderRequest(RESERVATION_ID));

    assertThat(result.created()).isFalse();
    assertThat(result.response().id()).isEqualTo(ORDER_ID);
    verify(reservationService, never()).findActiveForUser(any(), any());
    verify(paymentService, never()).createPending(any(), any());
  }

  @Test
  void should_rejectKey_when_itWasUsedWithAnotherReservation() {
    when(orderRepository.findByUserIdAndIdempotencyKey(USER_ID, "checkout-123"))
        .thenReturn(Optional.of(existingOrder()));

    assertThatThrownBy(
            () -> orderService.create(USER_ID, "checkout-123", new OrderRequest(UUID.randomUUID())))
        .isInstanceOf(DuplicateResourceException.class);
    verify(reservationService, never()).findActiveForUser(any(), any());
  }

  @Test
  void should_rejectAnotherOrder_when_reservationAlreadyHasAnOrder() {
    when(reservationService.findActiveForUser(RESERVATION_ID, USER_ID))
        .thenReturn(reservation());
    when(orderRepository.existsByReservationId(RESERVATION_ID)).thenReturn(true);

    assertThatThrownBy(
            () -> orderService.create(USER_ID, "checkout-456", new OrderRequest(RESERVATION_ID)))
        .isInstanceOf(DuplicateResourceException.class)
        .hasMessage("An order already exists for this reservation");

    verify(orderRepository, never()).save(any());
    verify(paymentService, never()).createPending(any(), any());
  }

  @Test
  void should_rejectBlankIdempotencyKey_beforeReadingResources() {
    assertThatThrownBy(() -> orderService.create(USER_ID, " ", new OrderRequest(RESERVATION_ID)))
        .isInstanceOf(BusinessRuleViolationException.class);

    verifyNoInteractions(orderRepository, reservationService, paymentService);
  }

  @Test
  void should_notCreateOrder_when_reservationIsNotActive() {
    when(reservationService.findActiveForUser(RESERVATION_ID, USER_ID))
        .thenThrow(new BusinessRuleViolationException("Reservation expired"));

    assertThatThrownBy(
            () -> orderService.create(USER_ID, "checkout-123", new OrderRequest(RESERVATION_ID)))
        .isInstanceOf(BusinessRuleViolationException.class);

    verify(orderRepository, never()).save(any());
    verify(paymentService, never()).createPending(any(), any());
  }

  @Test
  void should_returnOwnedOrder() {
    when(orderRepository.findByIdAndUserId(ORDER_ID, USER_ID))
        .thenReturn(Optional.of(existingOrder()));

    OrderResponse response = orderService.get(ORDER_ID, USER_ID);

    assertThat(response.id()).isEqualTo(ORDER_ID);
  }

  @Test
  void should_returnNotFound_when_orderBelongsToAnotherUser() {
    when(orderRepository.findByIdAndUserId(ORDER_ID, USER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> orderService.get(ORDER_ID, USER_ID))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void should_returnPaginatedOrderHistory() {
    PageRequest pageable = PageRequest.of(0, 20);
    when(orderRepository.findByUserIdOrderByCreatedAtDesc(USER_ID, pageable))
        .thenReturn(new PageImpl<>(List.of(existingOrder()), pageable, 1));

    PageResponse<OrderResponse> response = orderService.list(USER_ID, pageable);

    assertThat(response.content()).hasSize(1);
    assertThat(response.totalElements()).isEqualTo(1);
  }

  private static Reservation reservation() {
    Reservation reservation = org.mockito.Mockito.mock(Reservation.class);
    TicketType ticketType = org.mockito.Mockito.mock(TicketType.class);
    when(reservation.getId()).thenReturn(RESERVATION_ID);
    when(reservation.getTicketType()).thenReturn(ticketType);
    when(reservation.getQuantity()).thenReturn(2);
    when(ticketType.getId()).thenReturn(TICKET_TYPE_ID);
    when(ticketType.getPrice()).thenReturn(new BigDecimal("40.00"));
    return reservation;
  }

  private static Order existingOrder() {
    Order order = new Order(USER_ID, RESERVATION_ID, "checkout-123");
    order.addItem(TICKET_TYPE_ID, 2, new BigDecimal("40.00"));
    ReflectionTestUtils.setField(order, "id", ORDER_ID);
    return order;
  }
}
