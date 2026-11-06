package com.portfolio.ticketing.payment.domain;

import com.portfolio.ticketing.common.domain.BaseEntity;
import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "payments")
public class Payment extends BaseEntity {

  private static final String DEFAULT_CURRENCY = "USD";

  @Column(name = "order_id", nullable = false, updatable = false, unique = true)
  private UUID orderId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private PaymentStatus status;

  @Column(name = "provider_reference", unique = true, length = 100)
  private String providerReference;

  @Column(nullable = false, precision = 12, scale = 2)
  private BigDecimal amount;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(nullable = false, length = 3)
  private String currency;

  protected Payment() {}

  public Payment(UUID orderId, BigDecimal amount) {
    this.orderId = Objects.requireNonNull(orderId, "orderId");
    this.amount = Objects.requireNonNull(amount, "amount");
    if (amount.signum() < 0) {
      throw new BusinessRuleViolationException("Payment amount cannot be negative");
    }
    this.status = PaymentStatus.PENDING;
    this.currency = DEFAULT_CURRENCY;
  }

  public void markSucceeded(String providerReference) {
    if (providerReference == null || providerReference.isBlank()) {
      throw new BusinessRuleViolationException(
          "Provider reference is required for a successful payment");
    }
    status = status.transitionTo(PaymentStatus.SUCCEEDED);
    this.providerReference = providerReference;
  }

  public void markFailed() {
    status = status.transitionTo(PaymentStatus.FAILED);
  }

  public void refund() {
    status = status.transitionTo(PaymentStatus.REFUNDED);
  }

  public UUID getOrderId() {
    return orderId;
  }

  public PaymentStatus getStatus() {
    return status;
  }

  public String getProviderReference() {
    return providerReference;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getCurrency() {
    return currency;
  }
}
