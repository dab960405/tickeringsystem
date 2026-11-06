package com.portfolio.ticketing.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.InvalidStateTransitionException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderTest {

  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID RESERVATION_ID = UUID.randomUUID();
  private static final UUID TICKET_TYPE_ID = UUID.randomUUID();

  @Test
  void should_createPendingOrderWithUsdCurrency() {
    Order order = newOrder();

    assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    assertThat(order.getCurrency()).isEqualTo("USD");
    assertThat(order.getTotalAmount()).isEqualByComparingTo("0.00");
    assertThat(order.getItems()).isEmpty();
  }

  @Test
  void should_copyUnitPriceAndCalculateTotal_when_itemIsAdded() {
    Order order = newOrder();

    OrderItem item = order.addItem(TICKET_TYPE_ID, 2, new BigDecimal("40.00"));

    assertThat(order.getItems()).containsExactly(item);
    assertThat(item.getUnitPrice()).isEqualByComparingTo("40.00");
    assertThat(item.getSubtotal()).isEqualByComparingTo("80.00");
    assertThat(order.getTotalAmount()).isEqualByComparingTo("80.00");
  }

  @Test
  void should_rejectBlankIdempotencyKey() {
    assertThatThrownBy(() -> new Order(USER_ID, RESERVATION_ID, " "))
        .isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void should_rejectItem_when_orderIsNoLongerPending() {
    Order order = newOrder();
    order.cancel();

    assertThatThrownBy(() -> order.addItem(TICKET_TYPE_ID, 1, BigDecimal.TEN))
        .isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void should_followAllowedStateTransitions() {
    Order order = newOrder();

    order.markPaid();
    order.refund();

    assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
  }

  @Test
  void should_rejectInvalidStateTransition() {
    Order order = newOrder();
    order.markPaid();

    assertThatThrownBy(order::cancel).isInstanceOf(InvalidStateTransitionException.class);
  }

  @Test
  void should_identifyOwner() {
    Order order = newOrder();

    assertThat(order.isOwnedBy(USER_ID)).isTrue();
    assertThat(order.isOwnedBy(UUID.randomUUID())).isFalse();
  }

  private static Order newOrder() {
    return new Order(USER_ID, RESERVATION_ID, "checkout-123");
  }
}
