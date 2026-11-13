package com.portfolio.ticketing.order.domain;

import com.portfolio.ticketing.common.domain.BaseEntity;
import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.InvalidStateTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "commercial_generations")
public class CommercialGeneration extends BaseEntity {

  public static final int INITIAL_NUMBER = 1;

  @Column(name = "order_id", nullable = false, updatable = false)
  private UUID orderId;

  @Column(name = "generation_number", nullable = false, updatable = false)
  private int generationNumber;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private CommercialGenerationStatus status;

  protected CommercialGeneration() {}

  private CommercialGeneration(UUID orderId, int generationNumber) {
    this.orderId = Objects.requireNonNull(orderId, "orderId");
    if (generationNumber < INITIAL_NUMBER) {
      throw new BusinessRuleViolationException("Generation number must be positive");
    }
    this.generationNumber = generationNumber;
    this.status = CommercialGenerationStatus.ACTIVE;
  }

  public static CommercialGeneration initial(UUID orderId) {
    return new CommercialGeneration(orderId, INITIAL_NUMBER);
  }

  public CommercialGeneration successor() {
    if (status != CommercialGenerationStatus.SUPERSEDED) {
      throw new InvalidStateTransitionException(
          "A successor requires the current generation to be superseded first");
    }
    return new CommercialGeneration(orderId, generationNumber + 1);
  }

  public void supersede() {
    requireActive();
    status = CommercialGenerationStatus.SUPERSEDED;
  }

  public void cancel() {
    requireActive();
    status = CommercialGenerationStatus.CANCELLED;
  }

  public boolean isActive() {
    return status == CommercialGenerationStatus.ACTIVE;
  }

  private void requireActive() {
    if (status != CommercialGenerationStatus.ACTIVE) {
      throw new InvalidStateTransitionException("Generation in status " + status + " is final");
    }
  }

  public UUID getOrderId() {
    return orderId;
  }

  public int getGenerationNumber() {
    return generationNumber;
  }

  public CommercialGenerationStatus getStatus() {
    return status;
  }
}
