package com.portfolio.ticketing.order.domain;

import com.portfolio.ticketing.common.domain.BaseEntity;
import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "order_items")
public class OrderItem extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "order_id", nullable = false, updatable = false)
  private Order order;

  @Column(name = "ticket_type_id", nullable = false, updatable = false)
  private UUID ticketTypeId;

  @Column(nullable = false)
  private int quantity;

  @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
  private BigDecimal unitPrice;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(nullable = false, length = 3)
  private String currency;

  protected OrderItem() {}

  OrderItem(Order order, UUID ticketTypeId, int quantity, BigDecimal unitPrice) {
    this.order = Objects.requireNonNull(order, "order");
    this.ticketTypeId = Objects.requireNonNull(ticketTypeId, "ticketTypeId");
    this.unitPrice = Objects.requireNonNull(unitPrice, "unitPrice");
    if (quantity <= 0) {
      throw new BusinessRuleViolationException("Order item quantity must be positive");
    }
    if (unitPrice.signum() < 0) {
      throw new BusinessRuleViolationException("Order item unit price cannot be negative");
    }
    this.quantity = quantity;
    this.currency = Order.DEFAULT_CURRENCY;
  }

  public BigDecimal getSubtotal() {
    return unitPrice.multiply(BigDecimal.valueOf(quantity));
  }

  public Order getOrder() {
    return order;
  }

  public UUID getTicketTypeId() {
    return ticketTypeId;
  }

  public int getQuantity() {
    return quantity;
  }

  public BigDecimal getUnitPrice() {
    return unitPrice;
  }

  public String getCurrency() {
    return currency;
  }
}
