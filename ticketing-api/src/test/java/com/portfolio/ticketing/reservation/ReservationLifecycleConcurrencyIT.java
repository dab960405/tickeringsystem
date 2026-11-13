package com.portfolio.ticketing.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.ticketing.common.exception.InvalidStateTransitionException;
import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.repository.EventRepository;
import com.portfolio.ticketing.reservation.domain.Reservation;
import com.portfolio.ticketing.reservation.repository.ReservationRepository;
import com.portfolio.ticketing.reservation.service.ReservationExpirationService;
import com.portfolio.ticketing.reservation.service.ReservationService;
import com.portfolio.ticketing.support.AbstractIntegrationTest;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import com.portfolio.ticketing.tickettype.repository.TicketTypeRepository;
import com.portfolio.ticketing.tickettype.service.TicketTypeInventoryService;
import com.portfolio.ticketing.venue.domain.Venue;
import com.portfolio.ticketing.venue.repository.VenueRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "ticketing.reservation.expiration-fixed-delay-ms=600000")
class ReservationLifecycleConcurrencyIT extends AbstractIntegrationTest {

  @Autowired private ReservationService reservationService;
  @Autowired private ReservationExpirationService expirationService;
  @Autowired private ReservationRepository reservationRepository;
  @Autowired private TicketTypeRepository ticketTypeRepository;
  @Autowired private TicketTypeInventoryService inventoryService;
  @Autowired private EventRepository eventRepository;
  @Autowired private VenueRepository venueRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void should_keepConsumedInventory_when_confirmationAndExpirationRace() throws Exception {
    Fixture fixture = fixture(Instant.now().minusSeconds(30));

    List<Throwable> failures =
        runConcurrently(() -> confirm(fixture), () -> capture(expirationService::expireOverdue));

    assertThat(failures).allMatch(failure -> failure == null);
    assertState(fixture.reservationId(), "CONFIRMED", "CONSUMED", 0);
  }

  @Test
  void should_releaseExactlyOnce_when_cancellationAndExpirationRace() throws Exception {
    Fixture fixture = fixture(Instant.now().minusSeconds(30));

    List<Throwable> failures =
        runConcurrently(() -> cancel(fixture), () -> capture(expirationService::expireOverdue));

    assertThat(failures)
        .allMatch(failure -> failure == null || failure instanceof InvalidStateTransitionException);
    String status = reservationStatus(fixture.reservationId());
    assertThat(status).isIn("CANCELLED", "EXPIRED");
    assertState(fixture.reservationId(), status, "RELEASED", 1);
  }

  @Test
  void should_preserveOneConsistentOutcome_when_confirmationAndCancellationRace() throws Exception {
    Fixture fixture = fixture(Instant.now().plusSeconds(600));

    List<Throwable> failures = runConcurrently(() -> confirm(fixture), () -> cancel(fixture));

    assertThat(failures)
        .allMatch(failure -> failure == null || failure instanceof InvalidStateTransitionException);
    String status = reservationStatus(fixture.reservationId());
    if (status.equals("CONFIRMED")) {
      assertState(fixture.reservationId(), "CONFIRMED", "CONSUMED", 0);
    } else {
      assertState(fixture.reservationId(), "CANCELLED", "RELEASED", 1);
    }
  }

  @Test
  void should_releaseExactlyOnce_when_twoWorkersProcessTheSameReservation() throws Exception {
    Fixture fixture = fixture(Instant.now().minusSeconds(30));

    List<Throwable> failures =
        runConcurrently(
            () -> capture(expirationService::expireOverdue),
            () -> capture(expirationService::expireOverdue));

    assertThat(failures).allMatch(failure -> failure == null);
    assertState(fixture.reservationId(), "EXPIRED", "RELEASED", 1);
  }

  @Test
  void should_rollbackReservationTransition_when_inventoryRestorationFails() {
    Fixture fixture = fixture(Instant.now().minusSeconds(30));
    jdbcTemplate.execute(
        """
        CREATE OR REPLACE FUNCTION fail_stock_restore() RETURNS trigger AS $$
        BEGIN
          IF NEW.id = TG_ARGV[0]::uuid
              AND NEW.available_quantity > OLD.available_quantity THEN
            RAISE EXCEPTION 'forced stock restoration failure';
          END IF;
          RETURN NEW;
        END;
        $$ LANGUAGE plpgsql
        """);
    jdbcTemplate.execute(
        "CREATE TRIGGER fail_stock_restore_trigger BEFORE UPDATE ON ticket_types "
            + "FOR EACH ROW EXECUTE FUNCTION fail_stock_restore('"
            + fixture.ticketTypeId()
            + "')");

    try {
      assertThatThrownBy(expirationService::expireOverdue).hasMessageContaining("forced");
      assertState(fixture.reservationId(), "ACTIVE", "HELD", 0);
    } finally {
      jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_stock_restore_trigger ON ticket_types");
      jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_stock_restore()");
    }
  }

  private Throwable confirm(Fixture fixture) {
    return capture(
        () -> reservationService.confirmForPayment(fixture.reservationId(), fixture.userId()));
  }

  private Throwable cancel(Fixture fixture) {
    return capture(() -> {
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  fixture.userId().toString(),
                  null,
                  List.of(new SimpleGrantedAuthority("ROLE_USER"))));
      try {
        reservationService.cancel(fixture.reservationId(), fixture.userId());
      } finally {
        SecurityContextHolder.clearContext();
      }
    });
  }

  private static Throwable capture(Runnable action) {
    try {
      action.run();
      return null;
    } catch (Throwable failure) {
      return failure;
    }
  }

  private static List<Throwable> runConcurrently(Callable<Throwable>... actions) throws Exception {
    CountDownLatch ready = new CountDownLatch(actions.length);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Throwable>> futures =
          java.util.Arrays.stream(actions)
              .map(action ->
                  executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return action.call();
                  }))
              .toList();
      if (!ready.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError("Concurrent actions were not ready");
      }
      start.countDown();
      return futures.stream()
          .map(future -> {
            try {
              return future.get(30, TimeUnit.SECONDS);
            } catch (Exception exception) {
              throw new AssertionError("Concurrent action did not finish", exception);
            }
          })
          .toList();
    }
  }

  private Fixture fixture(Instant expiresAt) {
    Instant now = Instant.now();
    UUID userId =
        jdbcTemplate.queryForObject(
            "INSERT INTO users (email, password_hash, full_name, role)"
                + " VALUES (?, 'hash', 'Buyer', 'USER') RETURNING id",
            UUID.class,
            "lifecycle-" + UUID.randomUUID() + "@example.com");
    Venue venue = venueRepository.save(new Venue("Arena", "Lima", "Main St 1", 500));
    Event event =
        new Event(
            userId,
            venue,
            "Lifecycle Show",
            null,
            "MUSIC",
            now.plusSeconds(7200),
            now.plusSeconds(10800));
    event.publish();
    eventRepository.saveAndFlush(event);
    TicketType ticketType =
        ticketTypeRepository.saveAndFlush(
            new TicketType(
                event,
                "General",
                new BigDecimal("40.00"),
                1,
                now.minusSeconds(120),
                now.plusSeconds(3600),
                1));
    Reservation reservation =
        reservationRepository.saveAndFlush(new Reservation(userId, ticketType, 1, expiresAt));
    inventoryService.reserve(ticketType.getId(), 1);
    return new Fixture(userId, ticketType.getId(), reservation.getId());
  }

  private String reservationStatus(UUID reservationId) {
    return jdbcTemplate.queryForObject(
        "SELECT status FROM reservations WHERE id = ?", String.class, reservationId);
  }

  private void assertState(
      UUID reservationId, String status, String disposition, int availableQuantity) {
    assertThat(
            jdbcTemplate.queryForMap(
                "SELECT status, inventory_disposition FROM reservations WHERE id = ?",
                reservationId))
        .containsEntry("status", status)
        .containsEntry("inventory_disposition", disposition);
    UUID ticketTypeId =
        jdbcTemplate.queryForObject(
            "SELECT ticket_type_id FROM reservations WHERE id = ?", UUID.class, reservationId);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT available_quantity FROM ticket_types WHERE id = ?",
                Integer.class,
                ticketTypeId))
        .isEqualTo(availableQuantity);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT available_quantity >= 0 AND available_quantity <= total_quantity"
                    + " FROM ticket_types WHERE id = ?",
                Boolean.class,
                ticketTypeId))
        .isTrue();
  }

  private record Fixture(UUID userId, UUID ticketTypeId, UUID reservationId) {}
}
