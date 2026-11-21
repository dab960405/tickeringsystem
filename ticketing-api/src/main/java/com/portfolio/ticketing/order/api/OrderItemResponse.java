package com.portfolio.ticketing.order.api;

import com.portfolio.ticketing.order.domain.OrderItem;
import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemResponse(
    UUID ticketTypeId, int quantity, BigDecimal unitPrice, String currency, BigDecimal subtotal) {

  public static OrderItemResponse from(OrderItem item) {
    return new OrderItemResponse(
        item.getTicketTypeId(),
        item.getQuantity(),
        item.getUnitPrice(),
        item.getCurrency(),
        item.getSubtotal());
  }
}
