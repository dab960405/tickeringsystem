package com.portfolio.ticketing.venue.api;

import com.portfolio.ticketing.venue.domain.Venue;
import java.time.Instant;
import java.util.UUID;

public record VenueResponse(
    UUID id,
    String name,
    String city,
    String address,
    int capacity,
    Instant createdAt,
    Instant updatedAt) {

  public static VenueResponse from(Venue venue) {
    return new VenueResponse(
        venue.getId(),
        venue.getName(),
        venue.getCity(),
        venue.getAddress(),
        venue.getCapacity(),
        venue.getCreatedAt(),
        venue.getUpdatedAt());
  }
}
