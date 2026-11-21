package com.portfolio.ticketing.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.ticketing.common.exception.InsufficientStockException;
import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.repository.EventRepository;
import com.portfolio.ticketing.reservation.api.ReservationRequest;
import com.portfolio.ticketing.reservation.service.ReservationService;
import com.portfolio.ticketing.support.AbstractIntegrationTest;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import com.portfolio.ticketing.tickettype.repository.TicketTypeRepository;
import com.portfolio.ticketing.venue.domain.Venue;
import com.portfolio.ticketing.venue.repository.VenueRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "ticketing.reservation.expiration-fixed-delay-ms=600000")
class ReservationConcurrencyIT extends AbstractIntegrationTest {

  private static final int BUYERS = 100;
  private static final int STOCK = 10;

  @Autowired private ReservationService reservationService;
  @Autowired private TicketTypeRepository ticketTypeRepository;
  @Autowired private EventRepository eventRepository;
  @Autowired private VenueRepository venueRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @RepeatedTest(20)
  void should_reserveExactlyTenTickets_when_oneHundredBuyersCompete() throws Exception {
    Instant now = Instant.now();
    UUID userId = createUser();
    Venue venue = venueRepository.save(new Venue("Arena", "Lima", "Main St 1", 500));
    Event event =
        new Event(
            userId,
            venue,
            "Concurrency Show",
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
                STOCK,
                now.minusSeconds(120),
                now.plusSeconds(3600),
                4));

    CountDownLatch start = new CountDownLatch(1);
    List<Future<Throwable>> attempts = new ArrayList<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int index = 0; index < BUYERS; index++) {
        attempts.add(executor.submit(() -> attemptReservation(userId, ticketType.getId(), start)));
      }
      start.countDown();

      int successfulReservations = 0;
      int stockConflicts = 0;
      for (Future<Throwable> attempt : attempts) {
        Throwable failure = attempt.get(60, TimeUnit.SECONDS);
        if (failure == null) {
          successfulReservations++;
        } else if (failure instanceof InsufficientStockException) {
          stockConflicts++;
        } else {
          throw new AssertionError("Unexpected reservation failure", failure);
        }
      }

      ConcurrencyResult result =
          new ConcurrencyResult(
              successfulReservations,
              stockConflicts,
              availableQuantity(ticketType.getId()),
              reservedQuantity(ticketType.getId()));
      assertThat(result).isEqualTo(new ConcurrencyResult(10, 90, 0, 10));
    }
  }

  private Throwable attemptReservation(UUID userId, UUID ticketTypeId, CountDownLatch start) {
    try {
      start.await();
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  userId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
      reservationService.create(userId, new ReservationRequest(ticketTypeId, 1));
      return null;
    } catch (InsufficientStockException exception) {
      return exception;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return exception;
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private UUID createUser() {
    return jdbcTemplate.queryForObject(
        "INSERT INTO users (email, password_hash, full_name, role)"
            + " VALUES (?, 'hash', 'Concurrent Buyer', 'USER') RETURNING id",
        UUID.class,
        "concurrency-" + UUID.randomUUID() + "@example.com");
  }

  private int availableQuantity(UUID ticketTypeId) {
    return jdbcTemplate.queryForObject(
        "SELECT available_quantity FROM ticket_types WHERE id = ?", Integer.class, ticketTypeId);
  }

  private int reservedQuantity(UUID ticketTypeId) {
    return jdbcTemplate.queryForObject(
        "SELECT COALESCE(SUM(quantity), 0) FROM reservations"
            + " WHERE ticket_type_id = ? AND status = 'ACTIVE'",
        Integer.class,
        ticketTypeId);
  }

  private record ConcurrencyResult(
      int successfulReservations,
      int stockConflicts,
      int availableQuantity,
      int reservedQuantity) {}
}
