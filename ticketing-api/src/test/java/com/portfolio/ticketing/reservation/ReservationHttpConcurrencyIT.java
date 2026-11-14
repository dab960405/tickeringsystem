package com.portfolio.ticketing.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.repository.EventRepository;
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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = {
      "ticketing.reservation.expiration-fixed-delay-ms=600000",
      "ticketing.order.expiration-fixed-delay-ms=600000"
    })
class ReservationHttpConcurrencyIT extends AbstractIntegrationTest {

  private static final int BUYERS = 100;
  private static final int STOCK = 10;

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private JwtEncoder jwtEncoder;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private EventRepository eventRepository;
  @Autowired private VenueRepository venueRepository;
  @Autowired private TicketTypeRepository ticketTypeRepository;

  @RepeatedTest(20)
  void should_returnTenCreatedAndNinetyConflicts_when_oneHundredHttpRequestsCompete()
      throws Exception {
    Instant now = Instant.now();
    List<UUID> userIds = createUsers(BUYERS);
    Venue venue = venueRepository.save(new Venue("Arena", "Lima", "Main St 1", 500));
    Event event =
        new Event(
            userIds.getFirst(),
            venue,
            "HTTP Concurrency Show",
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
    List<Future<HttpStatus>> attempts = new ArrayList<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (UUID userId : userIds) {
        String token = accessToken(userId);
        attempts.add(
            executor.submit(
                () -> {
                  start.await();
                  HttpHeaders headers = new HttpHeaders();
                  headers.setBearerAuth(token);
                  headers.setContentType(MediaType.APPLICATION_JSON);
                  HttpEntity<String> request =
                      new HttpEntity<>(
                          "{\"ticketTypeId\":\"" + ticketType.getId() + "\",\"quantity\":1}",
                          headers);
                  ResponseEntity<String> response =
                      restTemplate.exchange(
                          "/api/v1/reservations", HttpMethod.POST, request, String.class);
                  return HttpStatus.valueOf(response.getStatusCode().value());
                }));
      }

      start.countDown();
      int created = 0;
      int conflicts = 0;
      for (Future<HttpStatus> attempt : attempts) {
        HttpStatus status = attempt.get(60, TimeUnit.SECONDS);
        if (status == HttpStatus.CREATED) {
          created++;
        } else if (status == HttpStatus.CONFLICT) {
          conflicts++;
        } else {
          throw new AssertionError("Unexpected reservation HTTP status: " + status);
        }
      }

      assertThat(created).isEqualTo(10);
      assertThat(conflicts).isEqualTo(90);
      assertThat(availableQuantity(ticketType.getId())).isZero();
      assertThat(activeReservedQuantity(ticketType.getId())).isEqualTo(10);
    }
  }

  private List<UUID> createUsers(int count) {
    List<UUID> userIds = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      userIds.add(
          jdbcTemplate.queryForObject(
              "INSERT INTO users (email, password_hash, full_name, role)"
                  + " VALUES (?, 'hash', 'HTTP Concurrent Buyer', 'USER') RETURNING id",
              UUID.class,
              "http-concurrency-" + UUID.randomUUID() + "@example.com"));
    }
    return userIds;
  }

  private String accessToken(UUID userId) {
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer("ticketing-api")
            .subject(userId.toString())
            .issuedAt(now)
            .expiresAt(now.plusSeconds(600))
            .claim("role", "USER")
            .claim("type", "access")
            .build();
    return jwtEncoder
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims))
        .getTokenValue();
  }

  private int availableQuantity(UUID ticketTypeId) {
    return jdbcTemplate.queryForObject(
        "SELECT available_quantity FROM ticket_types WHERE id = ?", Integer.class, ticketTypeId);
  }

  private int activeReservedQuantity(UUID ticketTypeId) {
    return jdbcTemplate.queryForObject(
        "SELECT COALESCE(SUM(quantity), 0) FROM reservations"
            + " WHERE ticket_type_id = ? AND status = 'ACTIVE'",
        Integer.class,
        ticketTypeId);
  }
}
