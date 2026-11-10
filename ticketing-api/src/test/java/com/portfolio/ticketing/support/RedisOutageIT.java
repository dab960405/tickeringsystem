package com.portfolio.ticketing.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.tickettype.api.TicketTypeCatalogEntry;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import com.portfolio.ticketing.tickettype.service.TicketTypeCatalogCacheService;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration-test fixture with isolated PostgreSQL and Redis containers.
 *
 * <p>This class deliberately does not extend {@link AbstractIntegrationTest}: its Redis instance
 * must remain independent from the JVM-shared Redis container used by the other integration tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class RedisOutageIT {

  private static final Logger log = LoggerFactory.getLogger(RedisOutageIT.class);

  private static final Instant SALE_START = Instant.parse("2030-01-01T00:00:00Z");
  private static final Instant SALE_END = Instant.parse("2030-01-10T00:00:00Z");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine"));

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4.12-alpine")).withExposedPorts(6379);

  @Autowired private RedisConnectionFactory redisConnectionFactory;

  @Autowired private StringRedisTemplate redisTemplate;

  @Autowired private TicketTypeCatalogCacheService catalogCacheService;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private EntityManager entityManager;

  @Autowired private PlatformTransactionManager transactionManager;

  @Autowired private MockMvc mockMvc;

  private UUID eventId;
  private UUID ticketTypeId;
  private boolean redisPaused;

  @DynamicPropertySource
  static void redisProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("spring.data.redis.timeout", () -> "500ms");
    registry.add("spring.data.redis.connect-timeout", () -> "500ms");
  }

  @BeforeEach
  void setUp() {
    flushDedicatedRedis();

    UUID organizerId =
        jdbcTemplate.queryForObject(
            "INSERT INTO users (email, password_hash, full_name, role)"
                + " VALUES (?, 'hash', 'Organizer', 'ORGANIZER') RETURNING id",
            UUID.class,
            "redis-outage-" + UUID.randomUUID() + "@example.com");

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              Venue venue = new Venue("Outage Arena", "Popayan", "Main St 1", 500);
              entityManager.persist(venue);

              Event event =
                  new Event(
                      organizerId,
                      venue,
                      "Redis outage event",
                      "Event used by the Redis outage integration test",
                      "MUSIC",
                      Instant.parse("2030-01-10T20:00:00Z"),
                      Instant.parse("2030-01-10T23:00:00Z"));
              entityManager.persist(event);

              TicketType ticketType =
                  new TicketType(
                      event, "General", new BigDecimal("40.00"), 100, SALE_START, SALE_END, 4);
              entityManager.persist(ticketType);

              event.publish();
              entityManager.flush();

              eventId = event.getId();
              ticketTypeId = ticketType.getId();
            });
  }

  @AfterEach
  void restoreRedisIfNecessary() {
    unpauseRedisIfNecessary();
  }

  @Test
  void should_fallBackToPostgresDuringRedisOutageAndRecoverAfterRedisReturns() throws Exception {
    List<TicketTypeCatalogEntry> expectedCatalog = catalogCacheService.listForEvent(eventId);
    assertThat(expectedCatalog).hasSize(1);
    assertThat(expectedCatalog.getFirst().name()).isEqualTo("General");

    String expectedEventResponse =
        mockMvc
            .perform(get("/api/v1/events/{id}", eventId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.ticketTypes[0].name").value("General"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    String cacheKey = catalogCacheKey();
    redisTemplate.delete(cacheKey);
    assertThat(redisTemplate.hasKey(cacheKey)).isFalse();

    pauseRedis();

    try {
      long catalogStartedAt = System.nanoTime();
      List<TicketTypeCatalogEntry> catalogDuringOutage = catalogCacheService.listForEvent(eventId);
      long catalogLatencyMillis = elapsedMillis(catalogStartedAt);

      assertThat(catalogDuringOutage).isEqualTo(expectedCatalog);
      assertThat(catalogLatencyMillis).isGreaterThanOrEqualTo(400);
      log.info("Redis paused: ticket-type catalog fallback completed in {} ms", catalogLatencyMillis);

      long eventStartedAt = System.nanoTime();
      MvcResult eventDuringOutage =
          mockMvc
              .perform(get("/api/v1/events/{id}", eventId))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.status").value("PUBLISHED"))
              .andExpect(jsonPath("$.ticketTypes[0].name").value("General"))
              .andReturn();
      long eventLatencyMillis = elapsedMillis(eventStartedAt);

      assertThat(eventDuringOutage.getResponse().getContentAsString())
          .isEqualTo(expectedEventResponse);
      assertThat(eventLatencyMillis).isGreaterThanOrEqualTo(400);
      log.info("Redis paused: public event GET fallback completed in {} ms", eventLatencyMillis);
    } finally {
      unpauseRedisIfNecessary();
    }

    await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(
            () -> {
              redisTemplate.delete(cacheKey);
              catalogCacheService.listForEvent(eventId);
              assertThat(redisTemplate.hasKey(cacheKey)).isTrue();
            });

    // Change PostgreSQL directly: the following read must return the value repopulated in Redis.
    jdbcTemplate.update(
        "UPDATE ticket_types SET name = ? WHERE id = ?", "Database-only change", ticketTypeId);

    List<TicketTypeCatalogEntry> recoveredCatalog = catalogCacheService.listForEvent(eventId);
    assertThat(recoveredCatalog)
        .extracting(TicketTypeCatalogEntry::name)
        .containsExactly("General");

    String recoveredEventResponse =
        mockMvc
            .perform(get("/api/v1/events/{id}", eventId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.ticketTypes[0].name").value("General"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(recoveredEventResponse).isEqualTo(expectedEventResponse);
  }

  @Test
  void should_useDedicatedRedisContainerInSpringContext() {
    assertThat(redisConnectionFactory).isInstanceOf(LettuceConnectionFactory.class);
    LettuceConnectionFactory lettuceConnectionFactory =
        (LettuceConnectionFactory) redisConnectionFactory;

    assertThat(lettuceConnectionFactory.getPort())
        .isEqualTo(REDIS.getMappedPort(6379))
        .isNotEqualTo(AbstractIntegrationTest.REDIS.getMappedPort(6379));
  }

  @Test
  void should_configureShortCommandTimeoutOnLettuceClient() {
    LettuceConnectionFactory lettuceConnectionFactory =
        (LettuceConnectionFactory) redisConnectionFactory;

    assertThat(lettuceConnectionFactory.getClientConfiguration().getCommandTimeout())
        .isEqualTo(Duration.ofMillis(500));
  }

  @Test
  void should_roundTripValueThroughContextRedis() {
    String key = "ticketing:redis-outage-it:" + UUID.randomUUID();
    try {
      redisTemplate.opsForValue().set(key, "connected");
      assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("connected");
    } finally {
      redisTemplate.delete(key);
    }
  }

  private void flushDedicatedRedis() {
    RedisConnection connection = redisConnectionFactory.getConnection();
    try {
      connection.serverCommands().flushAll();
    } finally {
      connection.close();
    }
  }

  private void pauseRedis() {
    REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    redisPaused = true;
  }

  private void unpauseRedisIfNecessary() {
    if (!redisPaused) {
      return;
    }
    try {
      REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
    } catch (RuntimeException exception) {
      log.debug("Redis container was already unpaused or is no longer available", exception);
    } finally {
      redisPaused = false;
    }
  }

  private String catalogCacheKey() {
    return TicketTypeCatalogCacheService.CACHE_NAME + "::" + eventId;
  }

  private static long elapsedMillis(long startedAtNanos) {
    return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
  }
}
