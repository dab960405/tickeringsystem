package com.portfolio.ticketing.order.domain;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.test.util.ReflectionTestUtils;

public final class CommercialFixtures {

  private CommercialFixtures() {}

  public static Order paidOrder(int quantity) {
    Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), "key-" + UUID.randomUUID());
    withId(order);
    withId(order.addItem(UUID.randomUUID(), quantity, new BigDecimal("40.00")));
    return order;
  }

  public static CommercialGeneration activeGeneration(Order order) {
    return withId(CommercialGeneration.initial(order.getId()));
  }

  public static CommercialUnit unit(OrderItem item, int unitNumber) {
    return withId(new CommercialUnit(item, unitNumber));
  }

  public static <T> T withId(T entity) {
    ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
    return entity;
  }
}
