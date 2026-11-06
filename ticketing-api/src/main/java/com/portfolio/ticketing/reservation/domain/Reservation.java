package com.portfolio.ticketing.reservation.domain;

import com.portfolio.ticketing.common.domain.BaseEntity;
import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.InvalidStateTransitionException;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "reservations")
public class Reservation extends BaseEntity {

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "ticket_type_id", nullable = false, updatable = false)
  private TicketType ticketType;

  @Column(nullable = false)
  private int quantity;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private ReservationStatus status;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  protected Reservation() {}

  public Reservation(UUID userId, TicketType ticketType, int quantity, Instant expiresAt) {
    this.userId = Objects.requireNonNull(userId, "userId");
    this.ticketType = Objects.requireNonNull(ticketType, "ticketType");
    this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    if (quantity <= 0) {
      throw new BusinessRuleViolationException("Reservation quantity must be positive");
    }
    this.quantity = quantity;
    this.status = ReservationStatus.ACTIVE;
  }

  public boolean isActiveAt(Instant now) {
    return status == ReservationStatus.ACTIVE && expiresAt.isAfter(now);
  }

  public void confirm() {
    transitionTo(ReservationStatus.CONFIRMED);
  }

  public void expire() {
    transitionTo(ReservationStatus.EXPIRED);
  }

  public void cancel() {
    transitionTo(ReservationStatus.CANCELLED);
  }

  public boolean isOwnedBy(UUID userId) {
    return this.userId.equals(userId);
  }

  private void transitionTo(ReservationStatus target) {
    if (status != ReservationStatus.ACTIVE) {
      throw new InvalidStateTransitionException(
          "Reservation cannot move from " + status + " to " + target);
    }
    status = target;
  }

  public UUID getUserId() {
    return userId;
  }

  public TicketType getTicketType() {
    return ticketType;
  }

  public int getQuantity() {
    return quantity;
  }

  public ReservationStatus getStatus() {
    return status;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }
}
