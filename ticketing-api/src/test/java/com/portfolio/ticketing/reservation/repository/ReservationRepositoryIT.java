package com.portfolio.ticketing.reservation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.ticketing.common.config.JpaAuditingConfig;
import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.reservation.domain.Reservation;
import com.portfolio.ticketing.support.AbstractIntegrationTest;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import com.portfolio.ticketing.tickettype.repository.TicketTypeRepository;
import com.portfolio.ticketing.venue.domain.Venue;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@Import(JpaAuditingConfig.class)
class ReservationRepositoryIT extends AbstractIntegrationTest {

  private static final Instant NOW = Instant.parse("2030-01-01T12:00:00Z");

  @Autowired private ReservationRepository reservationRepository;
  @Autowired private TicketTypeRepository ticketTypeRepository;
  @Autowired private TestEntityManager entityManager;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID userId;
  private TicketType ticketType;

  @BeforeEach
  void setUp() {
    userId =
        jdbcTemplate.queryForObject(
            "INSERT INTO users (email, password_hash, full_name, role)"
                + " VALUES (?, 'hash', 'Buyer', 'USER') RETURNING id",
            UUID.class,
            "buyer-" + UUID.randomUUID() + "@example.com");
    Venue venue = entityManager.persist(new Venue("Arena", "Lima", "Main St 1", 500));
    Event event =
        entityManager.persist(
            new Event(
                userId,
                venue,
                "Show",
                null,
                "MUSIC",
                NOW.plusSeconds(7200),
                NOW.plusSeconds(10800)));
    ticketType =
        ticketTypeRepository.saveAndFlush(
            new TicketType(
                event,
                "General",
                new BigDecimal("40.00"),
                10,
                NOW.minusSeconds(60),
                NOW.plusSeconds(3600),
                4));
  }

  @Test
  void should_decrementStockOnly_when_enoughTicketsRemain() {
    assertThat(ticketTypeRepository.reserveStock(ticketType.getId(), 3)).isEqualTo(1);
    assertThat(ticketTypeRepository.reserveStock(ticketType.getId(), 8)).isZero();

    assertThat(availableQuantity()).isEqualTo(7);
  }

  @Test
  void should_restoreStockWithoutExceedingTotal_when_reservationIsReleased() {
    ticketTypeRepository.reserveStock(ticketType.getId(), 4);

    assertThat(ticketTypeRepository.restoreStock(ticketType.getId(), 4)).isEqualTo(1);
    assertThat(ticketTypeRepository.restoreStock(ticketType.getId(), 1)).isZero();
    assertThat(availableQuantity()).isEqualTo(10);
  }

  @Test
  void should_cancelReservationOnlyOnce_when_itIsActiveAndOwned() {
    Reservation reservation =
        reservationRepository.saveAndFlush(
            new Reservation(userId, ticketType, 2, NOW.plusSeconds(600)));

    assertThat(reservationRepository.cancelActive(reservation.getId(), userId)).isEqualTo(1);
    assertThat(reservationRepository.cancelActive(reservation.getId(), userId)).isZero();
    assertThat(reservationRepository.cancelActive(reservation.getId(), UUID.randomUUID())).isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT status FROM reservations WHERE id = ?", String.class, reservation.getId()))
        .isEqualTo("CANCELLED");
  }

  private int availableQuantity() {
    return jdbcTemplate.queryForObject(
        "SELECT available_quantity FROM ticket_types WHERE id = ?",
        Integer.class,
        ticketType.getId());
  }
}
