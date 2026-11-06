package com.portfolio.ticketing.order.domain;

import com.portfolio.ticketing.common.domain.BaseEntity;
import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.InvalidStateTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;

@Entity
@Table(name = "tickets")
public class Ticket extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "order_item_id", nullable = false, updatable = false)
  private OrderItem orderItem;

  @Column(nullable = false, unique = true, length = 64)
  private String code;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private TicketStatus status;

  protected Ticket() {}

  public Ticket(OrderItem orderItem, String code) {
    this.orderItem = Objects.requireNonNull(orderItem, "orderItem");
    this.code = validateCode(code);
    this.status = TicketStatus.VALID;
  }

  public void markUsed() {
    if (status != TicketStatus.VALID) {
      throw new InvalidStateTransitionException("Only a valid ticket can be marked as used");
    }
    status = TicketStatus.USED;
  }

  public void cancel() {
    if (status != TicketStatus.VALID) {
      throw new InvalidStateTransitionException("Only a valid ticket can be cancelled");
    }
    status = TicketStatus.CANCELLED;
  }

  private static String validateCode(String code) {
    Objects.requireNonNull(code, "code");
    if (code.isBlank() || code.length() > 64) {
      throw new BusinessRuleViolationException("Ticket code must contain 1 to 64 characters");
    }
    return code;
  }

  public OrderItem getOrderItem() {
    return orderItem;
  }

  public String getCode() {
    return code;
  }

  public TicketStatus getStatus() {
    return status;
  }
}
