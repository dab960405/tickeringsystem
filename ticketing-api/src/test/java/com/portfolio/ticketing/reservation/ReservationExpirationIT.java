package com.portfolio.ticketing.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.repository.EventRepository;
import com.portfolio.ticketing.reservation.domain.Reservation;
import com.portfolio.ticketing.reservation.repository.ReservationRepository;
import com.portfolio.ticketing.reservation.service.ReservationExpirationService;
import com.portfolio.ticketing.support.AbstractIntegrationTest;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import com.portfolio.ticketing.tickettype.repository.TicketTypeRepository;
import com.portfolio.ticketing.tickettype.service.TicketTypeInventoryService;
import com.portfolio.ticketing.venue.domain.Venue;
import com.portfolio.ticketing.venue.repository.VenueRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "ticketing.reservation.expiration-fixed-delay-ms=600000")
class ReservationExpirationIT extends AbstractIntegrationTest {

  @Autowired private ReservationExpirationService expirationService;
  @Autowired private ReservationRepository reservationRepository;
  @Autowired private TicketTypeRepository ticketTypeRepository;
  @Autowired private TicketTypeInventoryService inventoryService;
  @Autowired private EventRepository eventRepository;
  @Autowired private VenueRepository venueRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void should_returnStockExactlyOnce_when_expirationRunsTwice() {
    Instant now = Instant.now();
    UUID userId =
        jdbcTemplate.queryForObject(
            "INSERT INTO users (email, password_hash, full_name, role)"
                + " VALUES (?, 'hash', 'Buyer', 'USER') RETURNING id",
            UUID.class,
            "expiration-" + UUID.randomUUID() + "@example.com");
    Venue venue = venueRepository.save(new Venue("Arena", "Lima", "Main St 1", 500));
    Event event =
        eventRepository.save(
            new Event(
                userId,
                venue,
                "Show",
                null,
                "MUSIC",
                now.plusSeconds(7200),
                now.plusSeconds(10800)));
    TicketType ticketType =
        ticketTypeRepository.saveAndFlush(
            new TicketType(
                event,
                "General",
                new BigDecimal("40.00"),
                10,
                now.minusSeconds(120),
                now.plusSeconds(3600),
                4));
    Reservation reservation =
        reservationRepository.saveAndFlush(
            new Reservation(userId, ticketType, 2, now.minusSeconds(60)));
    inventoryService.reserve(ticketType.getId(), 2);

    expirationService.expireOverdue();
    expirationService.expireOverdue();

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM reservations WHERE id = ?", String.class, reservation.getId()))
        .isEqualTo("EXPIRED");
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT available_quantity FROM ticket_types WHERE id = ?",
                Integer.class,
                ticketType.getId()))
        .isEqualTo(10);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT inventory_disposition FROM reservations WHERE id = ?",
                String.class,
                reservation.getId()))
        .isEqualTo("RELEASED");
  }
}
