package com.portfolio.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class EventEnvelopeTest {

  @Test
  void should_fillMetadataWithCurrentVersion_when_createdWithFactory() {
    // Given
    String data = "payload";

    // When
    EventEnvelope<String> envelope = EventEnvelope.of("OrderConfirmed", data);

    // Then
    assertThat(envelope)
        .satisfies(e -> {
          assertThat(e.eventId()).isNotNull();
          assertThat(e.occurredAt()).isNotNull();
          assertThat(e.version()).isEqualTo(EventEnvelope.CURRENT_VERSION);
          assertThat(e.data()).isEqualTo(data);
        });
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void should_reject_when_eventTypeIsBlank(String eventType) {
    assertThatThrownBy(
            () -> new EventEnvelope<>(UUID.randomUUID(), eventType, Instant.now(), 1, "x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void should_reject_when_versionIsLowerThanOne() {
    assertThatThrownBy(
            () -> new EventEnvelope<>(UUID.randomUUID(), "OrderConfirmed", Instant.now(), 0, "x"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
