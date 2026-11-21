package com.portfolio.ticketing.event.api;

import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.domain.EventStatus;
import java.time.Instant;
import java.util.UUID;

public record EventSummaryResponse(
    UUID id,
    String title,
    String category,
    Instant startsAt,
    Instant endsAt,
    EventStatus status,
    UUID venueId,
    String venueName,
    String city) {

  public static EventSummaryResponse from(Event event) {
    return new EventSummaryResponse(
        event.getId(),
        event.getTitle(),
        event.getCategory(),
        event.getStartsAt(),
        event.getEndsAt(),
        event.getStatus(),
        event.getVenue().getId(),
        event.getVenue().getName(),
        event.getVenue().getCity());
  }
}
