package com.portfolio.ticketing.event.api;

import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.domain.EventStatus;
import com.portfolio.ticketing.tickettype.api.TicketTypeResponse;
import com.portfolio.ticketing.venue.api.VenueResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EventDetailResponse(
    UUID id,
    UUID organizerId,
    String title,
    String description,
    String category,
    Instant startsAt,
    Instant endsAt,
    EventStatus status,
    VenueResponse venue,
    List<TicketTypeResponse> ticketTypes,
    Instant createdAt,
    Instant updatedAt) {

  public static EventDetailResponse from(Event event, List<TicketTypeResponse> ticketTypes) {
    return new EventDetailResponse(
        event.getId(),
        event.getOrganizerId(),
        event.getTitle(),
        event.getDescription(),
        event.getCategory(),
        event.getStartsAt(),
        event.getEndsAt(),
        event.getStatus(),
        VenueResponse.from(event.getVenue()),
        ticketTypes,
        event.getCreatedAt(),
        event.getUpdatedAt());
  }
}
