package com.portfolio.ticketing.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OrderItemTest {

  private static final UUID TICKET_TYPE_ID = UUID.randomUUID();

  @Test
  void should_calculateSubtotalFromCopiedPriceAndQuantity() {
    Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "key");

    OrderItem item = order.addItem(TICKET_TYPE_ID, 3, new BigDecimal("12.50"));

    assertThat(item.getSubtotal()).isEqualByComparingTo("37.50");
    assertThat(item.getCurrency()).isEqualTo("USD");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void should_rejectNonPositiveQuantity(int quantity) {
    Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "key");

    assertThatThrownBy(() -> order.addItem(TICKET_TYPE_ID, quantity, BigDecimal.TEN))
        .isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void should_rejectNegativeUnitPrice() {
    Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "key");

    assertThatThrownBy(() -> order.addItem(TICKET_TYPE_ID, 1, new BigDecimal("-0.01")))
        .isInstanceOf(BusinessRuleViolationException.class);
  }
}
