package com.portfolio.ticketing.reservation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.InvalidStateTransitionException;
import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import com.portfolio.ticketing.venue.domain.Venue;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ReservationTest {

  private static final UUID USER_ID = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2030-01-01T12:00:00Z");
  private static final Instant EXPIRES_AT = NOW.plusSeconds(600);

  private static TicketType ticketType() {
    Event event =
        new Event(
            UUID.randomUUID(),
            new Venue("Arena", "Lima", "Main St 1", 500),
            "Show",
            null,
            "MUSIC",
            NOW.plusSeconds(3600),
            NOW.plusSeconds(7200));
    return new TicketType(
        event,
        "General",
        new BigDecimal("40.00"),
        100,
        NOW.minusSeconds(60),
        NOW.plusSeconds(1800),
        4);
  }

  private static Reservation reservation() {
    return new Reservation(USER_ID, ticketType(), 2, EXPIRES_AT);
  }

  @Test
  void should_startActive_when_created() {
    Reservation reservation = reservation();

    assertThat(reservation)
        .extracting(Reservation::getStatus, Reservation::getQuantity, Reservation::getUserId)
        .containsExactly(ReservationStatus.ACTIVE, 2, USER_ID);
  }

  @Test
  void should_reject_when_quantityIsNotPositive() {
    assertThatThrownBy(() -> new Reservation(USER_ID, ticketType(), 0, EXPIRES_AT))
        .isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void should_beActive_when_expirationIsInTheFuture() {
    assertThat(reservation().isActiveAt(NOW)).isTrue();
  }

  @Test
  void should_notBeActive_when_expirationHasPassed() {
    assertThat(reservation().isActiveAt(EXPIRES_AT)).isFalse();
  }

  static Stream<Arguments> terminalTransitions() {
    return Stream.of(
        Arguments.of((Consumer<Reservation>) Reservation::confirm, ReservationStatus.CONFIRMED),
        Arguments.of((Consumer<Reservation>) Reservation::expire, ReservationStatus.EXPIRED),
        Arguments.of((Consumer<Reservation>) Reservation::cancel, ReservationStatus.CANCELLED));
  }

  @ParameterizedTest
  @MethodSource("terminalTransitions")
  void should_transition_when_reservationIsActive(
      Consumer<Reservation> transition, ReservationStatus expected) {
    Reservation reservation = reservation();

    transition.accept(reservation);

    assertThat(reservation.getStatus()).isEqualTo(expected);
  }

  @ParameterizedTest
  @MethodSource("terminalTransitions")
  void should_rejectTransition_when_reservationIsAlreadyTerminal(
      Consumer<Reservation> transition, ReservationStatus expected) {
    Reservation reservation = reservation();
    transition.accept(reservation);

    assertThatThrownBy(() -> transition.accept(reservation))
        .isInstanceOf(InvalidStateTransitionException.class);
  }

  @Test
  void should_recognizeOwner_when_userIdMatches() {
    assertThat(reservation().isOwnedBy(USER_ID)).isTrue();
  }

  @Test
  void should_notRecognizeOwner_when_userIdDiffers() {
    assertThat(reservation().isOwnedBy(UUID.randomUUID())).isFalse();
  }
}
