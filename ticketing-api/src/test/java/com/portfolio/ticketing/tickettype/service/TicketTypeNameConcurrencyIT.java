package com.portfolio.ticketing.tickettype.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.portfolio.ticketing.common.exception.DuplicateResourceException;
import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.repository.EventRepository;
import com.portfolio.ticketing.support.AbstractIntegrationTest;
import com.portfolio.ticketing.tickettype.api.TicketTypeRequest;
import com.portfolio.ticketing.tickettype.repository.TicketTypeRepository;
import com.portfolio.ticketing.venue.domain.Venue;
import com.portfolio.ticketing.venue.repository.VenueRepository;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * BACK-01: the organizer's case-insensitive ticket-type name contract must also hold across
 * concurrent transactions. All database and Redis connections use disposable Testcontainers.
 */
@SpringBootTest(properties = "ticketing.reservation.expiration-fixed-delay-ms=600000")
class TicketTypeNameConcurrencyIT extends AbstractIntegrationTest {

  private static final Instant SALE_START = Instant.parse("2030-01-01T00:00:00Z");
  private static final Instant SALE_END = Instant.parse("2030-01-10T00:00:00Z");

  @Autowired private TicketTypeService service;
  @Autowired private TicketTypeRepository ticketTypes;
  @Autowired private EventRepository events;
  @Autowired private VenueRepository venues;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;

  private UUID eventId;
  private UUID organizerId;

  @BeforeEach
  void setUp() {
    organizerId =
        jdbc.queryForObject(
            "INSERT INTO users (email, password_hash, full_name, role)"
                + " VALUES (?, 'hash', 'Organizer', 'ORGANIZER') RETURNING id",
            UUID.class,
            "name-race-" + UUID.randomUUID() + "@example.com");
    Venue venue = venues.save(new Venue("Name Race Arena", "Lima", "Main St 1", 500));
    Event event =
        events.save(
            new Event(
                organizerId,
                venue,
                "Name race",
                null,
                "MUSIC",
                Instant.parse("2030-01-10T20:00:00Z"),
                Instant.parse("2030-01-10T23:00:00Z")));
    eventId = event.getId();
    authenticate();
  }

  @AfterEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void should_waitForSharedEventLock_beforeCreatingName() throws Exception {
    // An E FOR SHARE owner must exclude a competing organizer name mutation (E FOR UPDATE).
    // With the former shared-lock implementation the mutation commits without waiting.
    try (Connection blocker = dataSource.getConnection();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      blocker.setAutoCommit(false);
      try {
        try (PreparedStatement statement =
            blocker.prepareStatement("SELECT status FROM events WHERE id = ? FOR SHARE")) {
          statement.setObject(1, eventId);
          statement.executeQuery().close();
        }
        int blockerPid;
        try (PreparedStatement statement = blocker.prepareStatement("SELECT pg_backend_pid()");
            ResultSet result = statement.executeQuery()) {
          result.next();
          blockerPid = result.getInt(1);
        }

        CompletableFuture<Void> creator =
            CompletableFuture.runAsync(
                () -> asOrganizer(() -> service.create(eventId, request("VIP"))), executor);
        await()
            .atMost(Duration.ofSeconds(8))
            .pollInterval(Duration.ofMillis(25))
            .until(() -> creator.isDone() || eventLockWaitsFor(blockerPid));
        assertThat(creator)
            .as("a ticket-type name write must wait for the existing E FOR SHARE lock")
            .isNotDone();
        assertThat(eventLockWaitsFor(blockerPid)).isTrue();
        blocker.rollback();
        creator.get(10, TimeUnit.SECONDS);
      } finally {
        blocker.rollback();
      }
    }

    assertThat(nameCount("vip")).isEqualTo(1);
  }

  @RepeatedTest(4)
  void should_createOnlyOneCaseVariant_whenTwoRequestsWriteConcurrently() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch start = new CountDownLatch(1);
      CompletableFuture<Boolean> first =
          CompletableFuture.supplyAsync(() -> createAfter(start, "VIP"), executor);
      CompletableFuture<Boolean> second =
          CompletableFuture.supplyAsync(() -> createAfter(start, "vip"), executor);
      start.countDown();
      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(nameCount("vip")).isEqualTo(1);
  }

  @RepeatedTest(4)
  void should_renameOnlyOneCaseVariant_whenTwoTicketTypesChangeConcurrently() throws Exception {
    UUID firstId = service.create(eventId, request("General")).id();
    UUID secondId = service.create(eventId, request("Standard")).id();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch start = new CountDownLatch(1);
      CompletableFuture<Boolean> first =
          CompletableFuture.supplyAsync(() -> renameAfter(start, firstId, "VIP"), executor);
      CompletableFuture<Boolean> second =
          CompletableFuture.supplyAsync(() -> renameAfter(start, secondId, "vip"), executor);
      start.countDown();
      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(nameCount("vip")).isEqualTo(1);
  }

  private boolean createAfter(CountDownLatch start, String name) {
    return afterStart(start, () -> service.create(eventId, request(name)));
  }

  private boolean renameAfter(CountDownLatch start, UUID id, String name) {
    return afterStart(start, () -> service.update(id, request(name)));
  }

  private boolean afterStart(CountDownLatch start, Supplier<?> action) {
    try {
      if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Start gate timed out");
      return asOrganizer(() -> {
        action.get();
        return true;
      });
    } catch (DuplicateResourceException duplicate) {
      return false;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    }
  }

  private <T> T asOrganizer(Supplier<T> action) {
    authenticate();
    try {
      return action.get();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private void authenticate() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                organizerId.toString(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ORGANIZER"))));
  }

  private boolean eventLockWaitsFor(int blockerPid) {
    Integer count =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM pg_stat_activity
            WHERE pid <> ?
              AND ? = ANY(pg_blocking_pids(pid))
              AND query ILIKE '%events%FOR UPDATE%'
            """,
            Integer.class,
            blockerPid,
            blockerPid);
    return count != null && count > 0;
  }

  private int nameCount(String name) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM ticket_types WHERE event_id = ? AND lower(name) = lower(?)",
        Integer.class,
        eventId,
        name);
  }

  private static TicketTypeRequest request(String name) {
    return new TicketTypeRequest(name, new BigDecimal("45.00"), 100, SALE_START, SALE_END, 4);
  }
}
