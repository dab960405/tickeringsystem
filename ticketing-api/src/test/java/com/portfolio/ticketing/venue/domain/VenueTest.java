package com.portfolio.ticketing.venue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VenueTest {

  @Test
  void should_keepAllFields_when_created() {
    Venue venue = new Venue("Arena", "Lima", "Main St 1", 500);

    assertThat(venue)
        .extracting(Venue::getName, Venue::getCity, Venue::getAddress, Venue::getCapacity)
        .containsExactly("Arena", "Lima", "Main St 1", 500);
  }

  @Test
  void should_replaceFields_when_updated() {
    Venue venue = new Venue("Arena", "Lima", "Main St 1", 500);

    venue.update("Stadium", "Cusco", "Second St 2", 1000);

    assertThat(venue)
        .extracting(Venue::getName, Venue::getCity, Venue::getAddress, Venue::getCapacity)
        .containsExactly("Stadium", "Cusco", "Second St 2", 1000);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void should_reject_when_capacityIsNotPositive(int capacity) {
    assertThatThrownBy(() -> new Venue("Arena", "Lima", "Main St 1", capacity))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void should_reject_when_nameIsNull() {
    assertThatThrownBy(() -> new Venue(null, "Lima", "Main St 1", 10))
        .isInstanceOf(NullPointerException.class);
  }
}
