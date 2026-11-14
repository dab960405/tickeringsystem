package com.portfolio.ticketing.event.api;

import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.domain.EventStatus;
import com.portfolio.ticketing.tickettype.api.TicketTypeResponse;
import com.portfolio.ticketing.venue.api.VenueResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EventMetadata(
    UUID id,
    UUID organizerId,
    String title,
    String description,
    String category,
    Instant startsAt,
    Instant endsAt,
    EventStatus status,
    VenueResponse venue,
    Instant createdAt,
    Instant updatedAt) {

  public static EventMetadata from(Event event) {
    return new EventMetadata(
        event.getId(),
        event.getOrganizerId(),
        event.getTitle(),
        event.getDescription(),
        event.getCategory(),
        event.getStartsAt(),
        event.getEndsAt(),
        event.getStatus(),
        VenueResponse.from(event.getVenue()),
        event.getCreatedAt(),
        event.getUpdatedAt());
  }

  public EventDetailResponse withTicketTypes(List<TicketTypeResponse> ticketTypes) {
    return new EventDetailResponse(
        id,
        organizerId,
        title,
        description,
        category,
        startsAt,
        endsAt,
        status,
        venue,
        ticketTypes,
        createdAt,
        updatedAt);
  }
}
