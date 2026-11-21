package com.portfolio.ticketing.order.domain;

import com.portfolio.ticketing.common.domain.BaseEntity;
import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/** One purchased ticket slot; its id survives every reissuance of the order. */
@Entity
@Table(name = "commercial_units")
public class CommercialUnit extends BaseEntity {

  @Column(name = "order_id", nullable = false, updatable = false)
  private UUID orderId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "order_item_id", nullable = false, updatable = false)
  private OrderItem orderItem;

  @Column(name = "unit_number", nullable = false, updatable = false)
  private int unitNumber;

  protected CommercialUnit() {}

  public CommercialUnit(OrderItem orderItem, int unitNumber) {
    this.orderItem = Objects.requireNonNull(orderItem, "orderItem");
    this.orderId = Objects.requireNonNull(orderItem.getOrder().getId(), "orderItem.order.id");
    if (unitNumber < 1 || unitNumber > orderItem.getQuantity()) {
      throw new BusinessRuleViolationException(
          "Unit number must be between 1 and the item quantity " + orderItem.getQuantity());
    }
    this.unitNumber = unitNumber;
  }

  public UUID getOrderId() {
    return orderId;
  }

  public OrderItem getOrderItem() {
    return orderItem;
  }

  public int getUnitNumber() {
    return unitNumber;
  }
}
