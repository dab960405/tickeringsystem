package com.portfolio.ticketing.tickettype.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.venue.domain.Venue;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TicketTypeTest {

  private static final Instant SALE_START = Instant.parse("2030-01-01T00:00:00Z");
  private static final Instant SALE_END = Instant.parse("2030-01-10T00:00:00Z");

  private static TicketType ticketType(int total) {
    Event event =
        new Event(
            UUID.randomUUID(),
            new Venue("Arena", "Lima", "Main St 1", 500),
            "Show",
            null,
            "MUSIC",
            Instant.parse("2030-01-10T20:00:00Z"),
            Instant.parse("2030-01-10T23:00:00Z"));
    return new TicketType(event, "VIP", new BigDecimal("50.00"), total, SALE_START, SALE_END, 4);
  }

  private static void setAvailable(TicketType ticketType, int available)
      throws ReflectiveOperationException {
    Field field = TicketType.class.getDeclaredField("availableQuantity");
    field.setAccessible(true);
    field.set(ticketType, available);
  }

  @Test
  void should_startFullyAvailableInUsd_when_created() {
    TicketType ticketType = ticketType(100);

    assertThat(ticketType)
        .extracting(
            TicketType::getAvailableQuantity, TicketType::getCurrency, TicketType::getMaxPerOrder)
        .containsExactly(100, "USD", 4);
  }

  @Test
  void should_keepCommittedQuantity_when_totalIsIncreased() throws ReflectiveOperationException {
    TicketType ticketType = ticketType(100);
    setAvailable(ticketType, 70);

    ticketType.update("VIP", new BigDecimal("60.00"), 150, SALE_START, SALE_END, 4);

    assertThat(ticketType.getAvailableQuantity()).isEqualTo(120);
  }

  @Test
  void should_reject_when_totalIsBelowCommittedQuantity() throws ReflectiveOperationException {
    TicketType ticketType = ticketType(100);
    setAvailable(ticketType, 70);

    assertThatThrownBy(() -> ticketType.update("VIP", BigDecimal.TEN, 29, SALE_START, SALE_END, 4))
        .isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void should_reject_when_priceIsNegative() {
    TicketType ticketType = ticketType(10);

    assertThatThrownBy(
            () -> ticketType.update("VIP", new BigDecimal("-1"), 10, SALE_START, SALE_END, 4))
        .isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void should_reject_when_maxPerOrderIsNotPositive() {
    TicketType ticketType = ticketType(10);

    assertThatThrownBy(() -> ticketType.update("VIP", BigDecimal.TEN, 10, SALE_START, SALE_END, 0))
        .isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void should_reject_when_saleWindowIsInverted() {
    TicketType ticketType = ticketType(10);

    assertThatThrownBy(() -> ticketType.update("VIP", BigDecimal.TEN, 10, SALE_END, SALE_START, 4))
        .isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void should_beOnSale_when_insideWindow() {
    assertThat(ticketType(10).isOnSale(SALE_START)).isTrue();
  }

  @Test
  void should_notBeOnSale_when_windowHasEnded() {
    assertThat(ticketType(10).isOnSale(SALE_END)).isFalse();
  }

  @Test
  void should_notBeOnSale_when_windowHasNotStarted() {
    assertThat(ticketType(10).isOnSale(SALE_START.minusSeconds(1))).isFalse();
  }
}
