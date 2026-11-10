package com.portfolio.ticketing.tickettype.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.support.AbstractIntegrationTest;
import com.portfolio.ticketing.tickettype.api.TicketTypeCatalogEntry;
import com.portfolio.ticketing.tickettype.api.TicketTypeRequest;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import com.portfolio.ticketing.venue.domain.Venue;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "ticketing.cache.ticket-types.ttl=2s",
      "spring.data.redis.timeout=200ms",
      "spring.data.redis.connect-timeout=200ms"
    })
class TicketTypeCatalogCacheIT extends AbstractIntegrationTest {

  private static final Instant SALE_START = Instant.parse("2030-01-01T00:00:00Z");
  private static final Instant SALE_END = Instant.parse("2030-01-10T00:00:00Z");

  @Autowired private TicketTypeCatalogCacheService catalogCacheService;
  @Autowired private TicketTypeService ticketTypeService;
  @Autowired private EntityManager entityManager;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private CacheManager cacheManager;
  @Autowired private TestRestTemplate restTemplate;
  @Autowired private RedisConnectionFactory redisConnectionFactory;

  private UUID organizerId;
  private UUID eventId;
  private UUID ticketTypeId;

  @BeforeEach
  void setUp() {
    organizerId =
        jdbcTemplate.queryForObject(
            "INSERT INTO users (email, password_hash, full_name, role)"
                + " VALUES (?, 'hash', 'Organizer', 'ORGANIZER') RETURNING id",
            UUID.class,
            "cache-test-" + UUID.randomUUID() + "@example.com");

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          Venue venue = new Venue("Cache Arena", "Lima", "Main St 1", 500);
          entityManager.persist(venue);
          Event event =
              new Event(
                  organizerId,
                  venue,
                  "Cache test event",
                  null,
                  "MUSIC",
                  Instant.parse("2030-01-10T20:00:00Z"),
                  Instant.parse("2030-01-10T23:00:00Z"));
          entityManager.persist(event);
          TicketType ticketType =
              new TicketType(
                  event, "General", new BigDecimal("40.00"), 100, SALE_START, SALE_END, 4);
          entityManager.persist(ticketType);
          entityManager.flush();
          event.publish();
          eventId = event.getId();
          ticketTypeId = ticketType.getId();
        });
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                organizerId.toString(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ORGANIZER"))));
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void should_serveCachedCatalogAndReloadAfterTtlExpires() {
    TicketTypeCatalogEntry expected =
        new TicketTypeCatalogEntry(
            ticketTypeId,
            "General",
            new BigDecimal("40.00"),
            TicketType.DEFAULT_CURRENCY,
            100,
            SALE_START,
            SALE_END,
            4);

    var initialCatalog = catalogCacheService.listForEvent(eventId);
    assertThat(initialCatalog).hasSize(1);
    assertThat(initialCatalog.getFirst()).isEqualTo(expected);

    jdbcTemplate.update("UPDATE ticket_types SET name = 'Updated' WHERE id = ?", ticketTypeId);

    var cachedCatalog = catalogCacheService.listForEvent(eventId);
    assertThat(cachedCatalog).hasSize(1);
    assertThat(cachedCatalog.getFirst()).isEqualTo(expected);

    await()
        .atMost(Duration.ofSeconds(6))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(() ->
            assertThat(catalogCacheService.listForEvent(eventId))
                .extracting(TicketTypeCatalogEntry::name)
                .containsExactly("Updated"));
  }

  @Test
  void should_invalidateCatalogAfterCreateCommits() {
    catalogCacheService.listForEvent(eventId);
    Cache cache = catalogCache();

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          ticketTypeService.create(eventId, request("VIP"));
          assertThat(cache.get(eventId)).isNotNull();
        });

    assertThat(cache.get(eventId)).isNull();
    assertThat(catalogCacheService.listForEvent(eventId))
        .extracting(TicketTypeCatalogEntry::name)
        .containsExactlyInAnyOrder("General", "VIP");
  }

  @Test
  void should_invalidateCatalogAfterUpdateCommits() {
    catalogCacheService.listForEvent(eventId);
    Cache cache = catalogCache();

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          ticketTypeService.update(ticketTypeId, request("Updated"));
          assertThat(cache.get(eventId)).isNotNull();
        });

    assertThat(cache.get(eventId)).isNull();
    assertThat(catalogCacheService.listForEvent(eventId))
        .extracting(TicketTypeCatalogEntry::name)
        .containsExactly("Updated");
  }

  @Test
  void should_servePostgresqlAndRecoverCache_whenRedisRestarts() {
    catalogCacheService.listForEvent(eventId);
    assertThat(catalogCache().get(eventId)).isNotNull();

    stopRedis();
    try {
      jdbcTemplate.update("UPDATE ticket_types SET name = 'Offline' WHERE id = ?", ticketTypeId);

      ResponseEntity<String> response =
          restTemplate.getForEntity("/api/v1/events/{id}", String.class, eventId);

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(response.getBody()).contains("\"name\":\"Offline\"");
    } finally {
      startRedis();
    }

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .ignoreExceptions()
        .untilAsserted(() -> {
          RedisConnection connection = redisConnectionFactory.getConnection();
          try {
            assertThat(connection.ping()).isEqualTo("PONG");
          } finally {
            connection.close();
          }
          assertThat(catalogCacheService.listForEvent(eventId))
              .extracting(TicketTypeCatalogEntry::name)
              .containsExactly("Offline");
          assertThat(catalogCache().get(eventId)).isNotNull();
        });

    jdbcTemplate.update("UPDATE ticket_types SET name = 'Not cached' WHERE id = ?", ticketTypeId);
    assertThat(catalogCacheService.listForEvent(eventId))
        .extracting(TicketTypeCatalogEntry::name)
        .containsExactly("Offline");
  }

  private Cache catalogCache() {
    return cacheManager.getCache(TicketTypeCatalogCacheService.CACHE_NAME);
  }

  private static TicketTypeRequest request(String name) {
    return new TicketTypeRequest(name, new BigDecimal("45.00"), 100, SALE_START, SALE_END, 4);
  }
}
