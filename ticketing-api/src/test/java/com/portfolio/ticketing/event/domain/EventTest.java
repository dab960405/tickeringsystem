package com.portfolio.ticketing.event.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.InvalidStateTransitionException;
import com.portfolio.ticketing.venue.domain.Venue;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class EventTest {

  private static final Instant START = Instant.parse("2030-01-10T20:00:00Z");
  private static final Instant END = START.plus(3, ChronoUnit.HOURS);
  private static final UUID ORGANIZER = UUID.randomUUID();

  private static Event draftEvent() {
    return new Event(
        ORGANIZER,
        new Venue("Arena", "Lima", "Main St 1", 500),
        "Show",
        "Desc",
        "MUSIC",
        START,
        END);
  }

  @Test
  void should_startAsDraft_when_created() {
    assertThat(draftEvent().getStatus()).isEqualTo(EventStatus.DRAFT);
  }

  @Test
  void should_keepDetails_when_created() {
    Event event = draftEvent();

    assertThat(event)
        .extracting(
            Event::getTitle,
            Event::getDescription,
            Event::getCategory,
            Event::getStartsAt,
            Event::getEndsAt,
            Event::getOrganizerId)
        .containsExactly("Show", "Desc", "MUSIC", START, END, ORGANIZER);
  }

  @Test
  void should_reject_when_endIsNotAfterStart() {
    Venue venue = new Venue("Arena", "Lima", "Main St 1", 500);

    assertThatThrownBy(() -> new Event(ORGANIZER, venue, "Show", null, "MUSIC", START, START))
        .isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void should_replaceDetails_when_updatedWhileDraft() {
    Event event = draftEvent();
    Venue newVenue = new Venue("Stadium", "Cusco", "Second St 2", 1000);

    event.update(newVenue, "New show", null, "THEATER", START, END.plusSeconds(60));

    assertThat(event)
        .extracting(Event::getVenue, Event::getTitle, Event::getCategory)
        .containsExactly(newVenue, "New show", "THEATER");
  }

  @Test
  void should_rejectUpdate_when_cancelled() {
    Event event = draftEvent();
    event.cancel();

    assertThatThrownBy(() -> event.update(event.getVenue(), "X", null, "MUSIC", START, END))
        .isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void should_recognizeOwner_when_idMatches() {
    assertThat(draftEvent().isOwnedBy(ORGANIZER)).isTrue();
  }

  @Test
  void should_notRecognizeOwner_when_idDiffers() {
    assertThat(draftEvent().isOwnedBy(UUID.randomUUID())).isFalse();
  }

  static Stream<Arguments> validTransitions() {
    return Stream.of(
        Arguments.of(List.<Consumer<Event>>of(Event::publish), EventStatus.PUBLISHED),
        Arguments.of(List.<Consumer<Event>>of(Event::cancel), EventStatus.CANCELLED),
        Arguments.of(
            List.<Consumer<Event>>of(Event::publish, Event::cancel), EventStatus.CANCELLED),
        Arguments.of(
            List.<Consumer<Event>>of(Event::publish, Event::finish), EventStatus.FINISHED));
  }

  @ParameterizedTest
  @MethodSource("validTransitions")
  void should_reachExpectedStatus_when_transitionIsValid(
      List<Consumer<Event>> steps, EventStatus expected) {
    Event event = draftEvent();

    steps.forEach(step -> step.accept(event));

    assertThat(event.getStatus()).isEqualTo(expected);
  }

  static Stream<Arguments> invalidTransitions() {
    return Stream.of(
        Arguments.of(List.<Consumer<Event>>of(Event::finish)),
        Arguments.of(List.<Consumer<Event>>of(Event::publish, Event::publish)),
        Arguments.of(List.<Consumer<Event>>of(Event::cancel, Event::publish)),
        Arguments.of(List.<Consumer<Event>>of(Event::cancel, Event::cancel)),
        Arguments.of(List.<Consumer<Event>>of(Event::publish, Event::finish, Event::cancel)));
  }

  @ParameterizedTest
  @MethodSource("invalidTransitions")
  void should_throwInvalidStateTransition_when_transitionIsNotAllowed(List<Consumer<Event>> steps) {
    Event event = draftEvent();

    assertThatThrownBy(() -> steps.forEach(step -> step.accept(event)))
        .isInstanceOf(InvalidStateTransitionException.class);
  }
}
