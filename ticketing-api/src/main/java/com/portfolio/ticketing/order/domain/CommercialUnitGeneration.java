package com.portfolio.ticketing.order.domain;

import com.portfolio.ticketing.common.domain.BaseEntity;
import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "commercial_unit_generations")
public class CommercialUnitGeneration extends BaseEntity {

  @Column(name = "order_id", nullable = false, updatable = false)
  private UUID orderId;

  @Column(name = "commercial_unit_id", nullable = false, updatable = false)
  private UUID commercialUnitId;

  @Column(name = "generation_id", nullable = false, updatable = false)
  private UUID generationId;

  protected CommercialUnitGeneration() {}

  public CommercialUnitGeneration(CommercialUnit unit, CommercialGeneration generation) {
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(generation, "generation");
    if (!unit.getOrderId().equals(generation.getOrderId())) {
      throw new BusinessRuleViolationException("Unit and generation belong to different orders");
    }
    this.orderId = unit.getOrderId();
    this.commercialUnitId = Objects.requireNonNull(unit.getId(), "unit.id");
    this.generationId = Objects.requireNonNull(generation.getId(), "generation.id");
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
}
