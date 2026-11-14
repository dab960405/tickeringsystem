package com.portfolio.ticketing.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.repository.EventRepository;
import com.portfolio.ticketing.event.service.EventLockService;
import com.portfolio.ticketing.reservation.api.ReservationRequest;
import com.portfolio.ticketing.reservation.service.ReservationService;
import com.portfolio.ticketing.support.AbstractIntegrationTest;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import com.portfolio.ticketing.tickettype.repository.TicketTypeRepository;
import com.portfolio.ticketing.venue.domain.Venue;
import com.portfolio.ticketing.venue.repository.VenueRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;

/** Proves the effective E → T lock order of reservation creation on real PostgreSQL rows. */
@SpringBootTest
@TestPropertySource(properties = "ticketing.reservation.expiration-fixed-delay-ms=600000")
class ReservationEventLockIT extends AbstractIntegrationTest {

  @Autowired private ReservationService reservationService;
  @Autowired private EventLockService eventLockService;
  @Autowired private EventRepository eventRepository;
  @Autowired private TicketTypeRepository ticketTypeRepository;
  @Autowired private VenueRepository venueRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSource dataSource;

  @AfterEach
  void clearSecurity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void should_requireCallerTransaction_when_lockingEvent() {
    UUID eventId = UUID.randomUUID();

    assertThatThrownBy(() -> eventLockService.lockShared(eventId))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThatThrownBy(() -> eventLockService.lockExclusive(eventId))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test
  void should_waitOnEventBeforeLockingTicketType_when_eventIsExclusivelyLocked() throws Exception {
    Fixture fixture = publishedFixture();

    try (Connection canceller = dataSource.getConnection();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      canceller.setAutoCommit(false);
      lockEventForUpdate(canceller, fixture.eventId());

      CompletableFuture<Void> reservation =
          CompletableFuture.runAsync(() -> reserve(fixture, 1), executor);

      awaitWaitingOnEventRow(fixture.eventId());
      assertThat(reservation).isNotDone();
      assertThat(ticketTypeIsUnlocked(fixture.ticketTypeId()))
          .as("T must not be locked while the reservation waits for E")
          .isTrue();

      canceller.rollback();
      reservation.get(10, TimeUnit.SECONDS);
    }

    assertThat(availableQuantity(fixture.ticketTypeId())).isEqualTo(9);
  }

  @Test
  void should_rejectReservationWithoutTakingStock_when_cancellationCommitsWhileWaiting()
      throws Exception {
    Fixture fixture = publishedFixture();

    try (Connection canceller = dataSource.getConnection();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      canceller.setAutoCommit(false);
      lockEventForUpdate(canceller, fixture.eventId());

      CompletableFuture<Void> reservation =
          CompletableFuture.runAsync(() -> reserve(fixture, 1), executor);
      awaitWaitingOnEventRow(fixture.eventId());

      try (PreparedStatement cancel =
          canceller.prepareStatement("UPDATE events SET status = 'CANCELLED' WHERE id = ?")) {
        cancel.setObject(1, fixture.eventId());
        assertThat(cancel.executeUpdate()).isEqualTo(1);
      }
      canceller.commit();

      assertThatThrownBy(() -> reservation.get(10, TimeUnit.SECONDS))
          .hasCauseInstanceOf(BusinessRuleViolationException.class);
    }

    assertThat(availableQuantity(fixture.ticketTypeId())).isEqualTo(10);
    assertThat(reservationCount(fixture.ticketTypeId())).isZero();
  }

  @Test
  void should_letSalesShareEventLock_when_noCancellationIsInProgress() throws Exception {
    Fixture fixture = publishedFixture();

    try (Connection seller = dataSource.getConnection();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      seller.setAutoCommit(false);
      try (PreparedStatement share =
          seller.prepareStatement("SELECT status FROM events WHERE id = ? FOR SHARE")) {
        share.setObject(1, fixture.eventId());
        share.executeQuery().close();
      }

      CompletableFuture.runAsync(() -> reserve(fixture, 2), executor).get(10, TimeUnit.SECONDS);
      seller.rollback();
    }

    assertThat(availableQuantity(fixture.ticketTypeId())).isEqualTo(8);
  }

  private void reserve(Fixture fixture, int quantity) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                fixture.userId().toString(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    try {
      reservationService.create(
          fixture.userId(), new ReservationRequest(fixture.ticketTypeId(), quantity));
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private static void lockEventForUpdate(Connection connection, UUID eventId) throws SQLException {
    try (PreparedStatement lock =
        connection.prepareStatement("SELECT status FROM events WHERE id = ? FOR UPDATE")) {
      lock.setObject(1, eventId);
      lock.executeQuery().close();
    }
  }

  /** Polls pg_locks/pg_stat_activity until a backend is blocked on the event row. */
  private void awaitWaitingOnEventRow(Connection blocker, UUID eventId) throws SQLException {
    int blockerPid;
    try (Statement statement = blocker.createStatement();
        ResultSet result = statement.executeQuery("SELECT pg_backend_pid()")) {
      result.next();
      blockerPid = result.getInt(1);
    }

    Awaitility.await()
        .alias("reservation waits for event row " + eventId)
        .atMost(Duration.ofSeconds(10))
        .pollInterval(Duration.ofMillis(25))
        .until(
            () -> {
              Integer waiting =
                  jdbcTemplate.queryForObject(
                      """
                      SELECT COUNT(*) FROM pg_stat_activity
                      WHERE wait_event_type = 'Lock'
                        AND ? = ANY(pg_blocking_pids(pid))
                        AND query ILIKE '%FROM events WHERE id%FOR SHARE%'
                      """,
                      Integer.class,
                      blockerPid);
              return waiting != null && waiting > 0;
            });
  }

  ate boolean ticketTypeIsUnlocked(UUID ticketTypeId) throws SQLException {
    try (Connection probe = dataSource.getConnection()) {
      probe.setAutoCommit(false);
      try (Statement timeout = probe.createStatement()) {
        timeout.execute("SET LOCAL lock_timeout = '200ms'");
      }
      try (PreparedStatement lock =
          probe.prepareStatement("SELECT id FROM ticket_types WHERE id = ? FOR UPDATE NOWAIT")) {
        lock.setObject(1, ticketTypeId);
        lock.executeQuery().close();
        return true;
      } catch (SQLException lockNotAvailable) {
        return false;
      } finally {
        probe.rollback();
      }
    }
  }

  private Fixture publishedFixture() {
    Instant now = Instant.now();
    UUID userId =
        jdbcTemplate.queryForObject(
            "INSERT INTO users (email, password_hash, full_name, role)"
                + " VALUES (?, 'hash', 'Lock Buyer', 'USER') RETURNING id",
            UUID.class,
            "event-lock-" + UUID.randomUUID() + "@example.com");
    Venue venue = venueRepository.save(new Venue("Arena", "Lima", "Main St 1", 500));
    Event event =
        new Event(
            userId,
            venue,
            "Lock Order Show",
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
                10,
                now.minusSeconds(120),
                now.plusSeconds(3600),
                4));
    return new Fixture(userId, event.getId(), ticketType.getId());
  }

  private int availableQuantity(UUID ticketTypeId) {
    return jdbcTemplate.queryForObject(
        "SELECT available_quantity FROM ticket_types WHERE id = ?", Integer.class, ticketTypeId);
  }

  private int reservationCount(UUID ticketTypeId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM reservations WHERE ticket_type_id = ?", Integer.class, ticketTypeId);
  }

  private record Fixture(UUID userId, UUID eventId, UUID ticketTypeId) {}
}
