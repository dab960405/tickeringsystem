package com.portfolio.ticketing.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.repository.EventRepository;
import com.portfolio.ticketing.reservation.api.ReservationRequest;
import com.portfolio.ticketing.reservation.api.ReservationResponse;
import com.portfolio.ticketing.reservation.service.ReservationConfirmationResult;
import com.portfolio.ticketing.reservation.service.ReservationExpirationService;
import com.portfolio.ticketing.reservation.service.ReservationRedisProjectionService;
import com.portfolio.ticketing.reservation.service.ReservationService;
import com.portfolio.ticketing.support.AbstractIntegrationTest;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import com.portfolio.ticketing.tickettype.repository.TicketTypeRepository;
import com.portfolio.ticketing.venue.domain.Venue;
import com.portfolio.ticketing.venue.repository.VenueRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@TestPropertySource(
    properties = {
      "ticketing.reservation.expiration-fixed-delay-ms=600000",
      "ticketing.order.expiration-fixed-delay-ms=600000"
    })
class ReservationRedisProjectionIT extends AbstractIntegrationTest {

  @Autowired private ReservationService reservationService;
  @Autowired private ReservationExpirationService expirationService;
  @Autowired private EventRepository eventRepository;
  @Autowired private TicketTypeRepository ticketTypeRepository;
  @Autowired private VenueRepository venueRepository;
  @Autowired private StringRedisTemplate redisTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void should_deferProjectionUntilTransactionCommits() {
    UUID reservationId = UUID.randomUUID();
    Instant expiresAt = Instant.now().plusSeconds(120);
    String key = key(reservationId);
    ReservationRedisProjectionService projectionService =
        new ReservationRedisProjectionService(
            redisTemplate, objectMapper, java.time.Clock.systemUTC());

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          projectionService.writeActiveAfterCommit(reservationId, expiresAt);
          assertThat(redisTemplate.hasKey(key)).isFalse();
        });

    assertThat(redisTemplate.hasKey(key)).isTrue();
    redisTemplate.delete(key);
  }

  @Test
  void should_writeActiveProjectionWithRemainingTtl_when_reservationCommits() throws Exception {
    Fixture fixture = createReservation();

    String key = key(fixture.reservation().id());
    String payload = redisTemplate.opsForValue().get(key);
    JsonNode json = objectMapper.readTree(payload);

    assertThat(json.path("status").asText()).isEqualTo("ACTIVE");
    assertThat(json.path("expiresAt").asText())
        .isEqualTo(fixture.reservation().expiresAt().toString());
    assertThat(redisTemplate.getExpire(key)).isBetween(1L, 600L);
  }

  @Test
  void should_deleteProjection_when_reservationIsCancelled() {
    Fixture fixture = createReservation();

    asUser(
        fixture.userId(),
        () -> {
          reservationService.cancel(fixture.reservation().id(), fixture.userId());
          return null;
        });

    assertThat(redisTemplate.hasKey(key(fixture.reservation().id()))).isFalse();
  }

  @Test
  void should_deleteProjection_when_reservationIsConfirmed() {
    Fixture fixture = createReservation();

    ReservationConfirmationResult result =
        reservationService.confirmForPayment(fixture.reservation().id(), fixture.userId());

    assertThat(result).isEqualTo(ReservationConfirmationResult.CONFIRMED);
    assertThat(redisTemplate.hasKey(key(fixture.reservation().id()))).isFalse();
  }

  @Test
  void should_deleteProjection_when_reservationExpires() {
    Fixture fixture = createReservation();
    jdbcTemplate.update(
        "UPDATE reservations SET expires_at = ? WHERE id = ?",
        Timestamp.from(Instant.now().minusSeconds(1)),
        fixture.reservation().id());

    expirationService.expireOverdue();

    assertThat(redisTemplate.hasKey(key(fixture.reservation().id()))).isFalse();
  }

  private Fixture createReservation() {
    Instant now = Instant.now();
    UUID userId =
        jdbcTemplate.queryForObject(
            "INSERT INTO users (email, password_hash, full_name, role)"
                + " VALUES (?, 'hash', 'Projection Buyer', 'USER') RETURNING id",
            UUID.class,
            "projection-" + UUID.randomUUID() + "@example.com");
    Venue venue = venueRepository.save(new Venue("Projection Arena", "Lima", "Main St 1", 500));
    Event event =
        new Event(
            userId,
            venue,
            "Projection Show",
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
                4,
                now.minusSeconds(120),
                now.plusSeconds(3600),
                4));
    ReservationResponse reservation =
        asUser(
            userId,
            () -> reservationService.create(userId, new ReservationRequest(ticketType.getId(), 1)));
    return new Fixture(userId, reservation);
  }

  private static String key(UUID reservationId) {
    return ReservationRedisProjectionService.KEY_PREFIX + reservationId;
  }

  private <T> T asUser(UUID userId, Supplier<T> action) {
    SecurityContext context = new SecurityContextImpl();
    context.setAuthentication(
        new UsernamePasswordAuthenticationToken(
            userId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    SecurityContextHolder.setContext(context);
    try {
      return action.get();
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private record Fixture(UUID userId, ReservationResponse reservation) {}
}
