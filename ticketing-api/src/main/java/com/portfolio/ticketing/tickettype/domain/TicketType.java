package com.portfolio.ticketing.tickettype.domain;

import com.portfolio.ticketing.common.domain.BaseEntity;
import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.event.domain.Event;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ticket_types")
public class TicketType extends BaseEntity {

  public static final String DEFAULT_CURRENCY = "USD";

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "event_id", nullable = false, updatable = false)
  private Event event;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(nullable = false, precision = 12, scale = 2)
  private BigDecimal price;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(nullable = false, length = 3)
  private String currency = DEFAULT_CURRENCY;

  @Column(name = "total_quantity", nullable = false)
  private int totalQuantity;

  @Column(name = "available_quantity", nullable = false)
  private int availableQuantity;

  // Protects concurrent organizer edits; stock changes use an atomic conditional UPDATE.
  @Version
  @Column(nullable = false)
  private long version;

  @Column(name = "sale_starts_at", nullable = false)
  private Instant saleStartsAt;

  @Column(name = "sale_ends_at", nullable = false)
  private Instant saleEndsAt;

  @Column(name = "max_per_order", nullable = false)
  private int maxPerOrder;

  protected TicketType() {}

  public TicketType(
      Event event,
      String name,
      BigDecimal price,
      int totalQuantity,
      Instant saleStartsAt,
      Instant saleEndsAt,
      int maxPerOrder) {
    this.event = Objects.requireNonNull(event, "event");
    this.availableQuantity = totalQuantity;
    applyDetails(name, price, totalQuantity, saleStartsAt, saleEndsAt, maxPerOrder);
  }

  public void update(
      String name,
      BigDecimal price,
      int totalQuantity,
      Instant saleStartsAt,
      Instant saleEndsAt,
      int maxPerOrder) {
    int newAvailable = totalQuantity - committedQuantity();
    if (newAvailable < 0) {
      throw new BusinessRuleViolationException(
          "Total quantity cannot be lower than the "
              + committedQuantity()
              + " tickets already sold or reserved");
    }
    applyDetails(name, price, totalQuantity, saleStartsAt, saleEndsAt, maxPerOrder);
    this.availableQuantity = newAvailable;
  }

  /** Tickets already sold or held by active reservations. */
  public int committedQuantity() {
    return totalQuantity - availableQuantity;
  }

  public boolean isOnSale(Instant now) {
    return !now.isBefore(saleStartsAt) && now.isBefore(saleEndsAt);
  }

  private void applyDetails(
      String name,
      BigDecimal price,
      int totalQuantity,
      Instant saleStartsAt,
      Instant saleEndsAt,
      int maxPerOrder) {
    Objects.requireNonNull(price, "price");
    Objects.requireNonNull(saleStartsAt, "saleStartsAt");
    Objects.requireNonNull(saleEndsAt, "saleEndsAt");
    if (price.signum() < 0) {
      throw new BusinessRuleViolationException("Price cannot be negative");
    }
    if (totalQuantity <= 0 || maxPerOrder <= 0) {
      throw new BusinessRuleViolationException("Quantities must be positive");
    }
    if (!saleEndsAt.isAfter(saleStartsAt)) {
      throw new BusinessRuleViolationException("Sale end must be after sale start");
    }
    this.name = Objects.requireNonNull(name, "name");
    this.price = price;
    this.totalQuantity = totalQuantity;
    this.saleStartsAt = saleStartsAt;
    this.saleEndsAt = saleEndsAt;
    this.maxPerOrder = maxPerOrder;
  }

  public Event getEvent() {
    return event;
  }

  public String getName() {
    return name;
  }

  public BigDecimal getPrice() {
    return price;
  }

  public String getCurrency() {
    return currency;
  }

  public int getTotalQuantity() {
    return totalQuantity;
  }

  public int getAvailableQuantity() {
    return availableQuantity;
  }

  public long getVersion() {
    return version;
  }

  public Instant getSaleStartsAt() {
    return saleStartsAt;
  }

  public Instant getSaleEndsAt() {
    return saleEndsAt;
  }

  public int getMaxPerOrder() {
    return maxPerOrder;
  }
}
