package com.portfolio.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Wrapper shared by every message published to the broker.
 *
 * @param eventId unique id used by consumers to discard duplicates
 * @param eventType logical name of the event, e.g. {@code OrderConfirmed}
 * @param occurredAt instant (UTC) when the business fact happened
 * @param version contract version of {@code data}
 * @param data event-specific payload
 */
public record EventEnvelope<T>(
    UUID eventId, String eventType, Instant occurredAt, int version, T data) {

  public static final int CURRENT_VERSION = 1;

  public EventEnvelope {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    Objects.requireNonNull(data, "data must not be null");
    if (eventType == null || eventType.isBlank()) {
      throw new IllegalArgumentException("eventType must not be blank");
    }
    if (version < 1) {
      throw new IllegalArgumentException("version must be >= 1");
    }
  }

  public static <T> EventEnvelope<T> of(String eventType, T data) {
    return new EventEnvelope<>(UUID.randomUUID(), eventType, Instant.now(), CURRENT_VERSION, data);
  }
}
