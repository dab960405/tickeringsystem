package com.portfolio.ticketing.order.domain;

import com.portfolio.ticketing.common.domain.BaseEntity;
import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "orders")
public class Order extends BaseEntity {

  public static final String DEFAULT_CURRENCY = "USD";

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "reservation_id", nullable = false, updatable = false)
  private UUID reservationId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private OrderStatus status;

  @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
  private BigDecimal totalAmount;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(nullable = false, length = 3)
  private String currency;

  @Column(name = "idempotency_key", nullable = false, updatable = false, length = 100)
  private String idempotencyKey;

  @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<OrderItem> items = new ArrayList<>();

  protected Order() {}

  public Order(UUID userId, UUID reservationId, String idempotencyKey) {
    this.userId = Objects.requireNonNull(userId, "userId");
    this.reservationId = Objects.requireNonNull(reservationId, "reservationId");
    this.idempotencyKey = validateIdempotencyKey(idempotencyKey);
    this.status = OrderStatus.PENDING_PAYMENT;
    this.totalAmount = BigDecimal.ZERO.setScale(2);
    this.currency = DEFAULT_CURRENCY;
  }

  public OrderItem addItem(UUID ticketTypeId, int quantity, BigDecimal unitPrice) {
    if (status != OrderStatus.PENDING_PAYMENT) {
      throw new BusinessRuleViolationException("Items can only be added while payment is pending");
    }
    OrderItem item = new OrderItem(this, ticketTypeId, quantity, unitPrice);
    items.add(item);
    totalAmount = totalAmount.add(item.getSubtotal());
    return item;
  }

  public void markPaid() {
    transitionTo(OrderStatus.PAID);
  }

  public void cancel() {
    transitionTo(OrderStatus.CANCELLED);
  }

  public void expire() {
    transitionTo(OrderStatus.EXPIRED);
  }

  public void refund() {
    transitionTo(OrderStatus.REFUNDED);
  }

  public boolean isOwnedBy(UUID userId) {
    return this.userId.equals(userId);
  }

  private void transitionTo(OrderStatus target) {
    status = status.transitionTo(target);
  }

  private static String validateIdempotencyKey(String key) {
    Objects.requireNonNull(key, "idempotencyKey");
    if (key.isBlank() || key.length() > 100) {
      throw new BusinessRuleViolationException("Idempotency key must contain 1 to 100 characters");
    }
    return key;
  }

  public UUID getUserId() {
    return userId;
  }

  public UUID getReservationId() {
    return reservationId;
  }

  public OrderStatus getStatus() {
    return status;
  }

  public BigDecimal getTotalAmount() {
    return totalAmount;
  }

  public String getCurrency() {
    return currency;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public List<OrderItem> getItems() {
    return Collections.unmodifiableList(items);
  }
}
