package com.portfolio.ticketing.event.domain;

import java.util.Set;

public enum EventStatus {
  DRAFT,
  PUBLISHED,
  CANCELLED,
  FINISHED;

  public boolean canTransitionTo(EventStatus target) {
    return switch (this) {
      case DRAFT -> Set.of(PUBLISHED, CANCELLED).contains(target);
      case PUBLISHED -> Set.of(CANCELLED, FINISHED).contains(target);
      case CANCELLED, FINISHED -> false;
    };
  }

  public boolean isEditable() {
    return this == DRAFT || this == PUBLISHED;
  }
}
