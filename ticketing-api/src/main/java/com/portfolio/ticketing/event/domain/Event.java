package com.portfolio.ticketing.event.domain;

import com.portfolio.ticketing.common.domain.BaseEntity;
import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.InvalidStateTransitionException;
import com.portfolio.ticketing.venue.domain.Venue;
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
@Table(name = "events")
public class Event extends BaseEntity {

  // Stored as a plain id: the user module is only reached through its service, never its entity.
  @Column(name = "organizer_id", nullable = false, updatable = false)
  private UUID organizerId;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "venue_id", nullable = false)
  private Venue venue;

  @Column(nullable = false, length = 200)
  private String title;

  @Column(columnDefinition = "text")
  private String description;

  @Column(nullable = false, length = 50)
  private String category;

  @Column(name = "starts_at", nullable = false)
  private Instant startsAt;

  @Column(name = "ends_at", nullable = false)
  private Instant endsAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private EventStatus status;

  protected Event() {}

  public Event(
      UUID organizerId,
      Venue venue,
      String title,
      String description,
      String category,
      Instant startsAt,
      Instant endsAt) {
    this.organizerId = Objects.requireNonNull(organizerId, "organizerId");
    this.status = EventStatus.DRAFT;
    applyDetails(venue, title, description, category, startsAt, endsAt);
  }

  public void update(
      Venue venue,
      String title,
      String description,
      String category,
      Instant startsAt,
      Instant endsAt) {
    if (!status.isEditable()) {
      throw new BusinessRuleViolationException("Event in status " + status + " cannot be edited");
    }
    applyDetails(venue, title, description, category, startsAt, endsAt);
  }

  public void publish() {
    transitionTo(EventStatus.PUBLISHED);
  }

  public void cancel() {
    transitionTo(EventStatus.CANCELLED);
  }

  public void finish() {
    transitionTo(EventStatus.FINISHED);
  }

  public boolean isOwnedBy(UUID userId) {
    return organizerId.equals(userId);
  }

  private void transitionTo(EventStatus target) {
    if (!status.canTransitionTo(target)) {
      throw new InvalidStateTransitionException(
          "Event cannot move from " + status + " to " + target);
    }
    this.status = target;
  }

  private void applyDetails(
      Venue venue,
      String title,
      String description,
      String category,
      Instant startsAt,
      Instant endsAt) {
    Objects.requireNonNull(startsAt, "startsAt");
    Objects.requireNonNull(endsAt, "endsAt");
    if (!endsAt.isAfter(startsAt)) {
      throw new BusinessRuleViolationException("Event end must be after its start");
    }
    this.venue = Objects.requireNonNull(venue, "venue");
    this.title = Objects.requireNonNull(title, "title");
    this.description = description;
    this.category = Objects.requireNonNull(category, "category");
    this.startsAt = startsAt;
    this.endsAt = endsAt;
  }

  public UUID getOrganizerId() {
    return organizerId;
  }

  public Venue getVenue() {
    return venue;
  }

  public String getTitle() {
    return title;
  }

  public String getDescription() {
    return description;
  }

  public String getCategory() {
    return category;
  }

  public Instant getStartsAt() {
    return startsAt;
  }

  public Instant getEndsAt() {
    return endsAt;
  }

  public EventStatus getStatus() {
    return status;
  }
}
