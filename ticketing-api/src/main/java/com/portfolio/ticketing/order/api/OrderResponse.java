package com.portfolio.ticketing.order.api;

import com.portfolio.ticketing.order.domain.FulfillmentStatus;
import com.portfolio.ticketing.order.domain.Order;
import com.portfolio.ticketing.order.domain.OrderStatus;
import com.portfolio.ticketing.order.domain.RefundStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
    UUID id,
    UUID reservationId,
    OrderStatus status,
    FulfillmentStatus fulfillmentStatus,
    RefundStatus refundStatus,
    BigDecimal totalAmount,
    String currency,
    List<OrderItemResponse> items,
    Instant createdAt) {

  public static OrderResponse from(Order order) {
    return new OrderResponse(
        order.getId(),
        order.getReservationId(),
        order.getStatus(),
        order.getFulfillmentStatus(),
        order.getRefundStatus(),
        order.getTotalAmount(),
        order.getCurrency(),
        order.getItems().stream().map(OrderItemResponse::from).toList(),
        order.getCreatedAt());
  }
}
