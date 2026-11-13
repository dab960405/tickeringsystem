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
import java.util.UUID;

@Entity
@Table(name = "tickets")
public class Ticket extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "order_item_id", nullable = false, updatable = false)
  private OrderItem orderItem;

  @Column(name = "order_id", nullable = false, updatable = false)
  private UUID orderId;

  @Column(name = "commercial_unit_id", nullable = false, updatable = false)
  private UUID commercialUnitId;

  @Column(name = "generation_id", nullable = false, updatable = false)
  private UUID generationId;

  @Column(nullable = false, unique = true, length = 64, updatable = false)
  private String code;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private TicketStatus status;

  protected Ticket() {}

  public Ticket(CommercialUnit unit, CommercialGeneration generation, String code) {
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(generation, "generation");
    if (!generation.isActive()) {
      throw new InvalidStateTransitionException(
          "Tickets can only be issued for an active generation");
    }
    if (!unit.getOrderId().equals(generation.getOrderId())) {
      throw new BusinessRuleViolationException("Unit and generation belong to different orders");
    }
    this.orderItem = unit.getOrderItem();
    this.orderId = unit.getOrderId();
    this.commercialUnitId = Objects.requireNonNull(unit.getId(), "unit.id");
    this.generationId = Objects.requireNonNull(generation.getId(), "generation.id");
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

  public UUID getOrderId() {
    return orderId;
  }

  public UUID getCommercialUnitId() {
    return commercialUnitId;
  }

  public UUID getGenerationId() {
    return generationId;
  }

  public String getCode() {
    return code;
  }

  public TicketStatus getStatus() {
    return status;
  }
}
