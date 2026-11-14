package com.portfolio.ticketing.event.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.portfolio.ticketing.common.api.PageResponse;
import com.portfolio.ticketing.event.api.EventDetailResponse;
import com.portfolio.ticketing.event.api.EventRequest;
import com.portfolio.ticketing.event.api.EventSummaryResponse;
import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.repository.EventFilter;
import com.portfolio.ticketing.support.AbstractIntegrationTest;
import com.portfolio.ticketing.tickettype.api.TicketTypeRequest;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import com.portfolio.ticketing.tickettype.service.TicketTypeService;
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
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "ticketing.cache.events.ttl=2s")
class EventCatalogCacheIT extends AbstractIntegrationTest {

  private static final Instant START = Instant.parse("2030-01-10T20:00:00Z");
  private static final Instant END = Instant.parse("2030-01-10T23:00:00Z");
  private static final Instant SALE_START = Instant.parse("2030-01-01T00:00:00Z");
  private static final Instant SALE_END = Instant.parse("2030-01-10T00:00:00Z");
  private static final EventFilter NO_FILTER = new EventFilter(null, null, null, null, null);
  private static final PageRequest POPULAR_PAGE =
      PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "startsAt"));

  @Autowired private EventService eventService;
  @Autowired private TicketTypeService ticketTypeService;
  @Autowired private EntityManager entityManager;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private CacheManager cacheManager;
  @Autowired private StringRedisTemplate redisTemplate;

  private UUID organizerId;
  private UUID venueId;
  private UUID eventId;
  private UUID ticketTypeId;

  @BeforeEach
  void setUp() {
    organizerId =
        jdbcTemplate.queryForObject(
            "INSERT INTO users (email, password_hash, full_name, role)"
                + " VALUES (?, 'hash', 'Organizer', 'ORGANIZER') RETURNING id",
            UUID.class,
            "event-cache-" + UUID.randomUUID() + "@example.com");

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          Venue venue = new Venue("Event Cache Arena", "Lima", "Main St 1", 500);
          entityManager.persist(venue);
          Event event = new Event(organizerId, venue, "Cached event", null, "MUSIC", START, END);
          entityManager.persist(event);
          TicketType ticketType =
              new TicketType(
                  event, "General", new BigDecimal("40.00"), 100, SALE_START, SALE_END, 4);
          entityManager.persist(ticketType);
          event.publish();
          entityManager.flush();
          venueId = venue.getId();
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
  void should_cacheMetadataWithoutInventoryAndReloadAfterTtl() {
    EventDetailResponse initial = eventService.getPublished(eventId);
    String redisKey = "ticketing:event-details::" + eventId;
    assertThat(redisTemplate.getExpire(redisKey)).isBetween(1L, 2L);

    jdbcTemplate.update("UPDATE events SET title = 'Fresh event' WHERE id = ?", eventId);
    jdbcTemplate.update(
        "UPDATE ticket_types SET available_quantity = 77 WHERE id = ?", ticketTypeId);

    EventDetailResponse cached = eventService.getPublished(eventId);
    assertThat(cached.title()).isEqualTo(initial.title());
    assertThat(cached.ticketTypes().getFirst().availableQuantity()).isEqualTo(77);

    await()
        .atMost(Duration.ofSeconds(6))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(
            () -> assertThat(eventService.getPublished(eventId).title()).isEqualTo("Fresh event"));
  }

  @Test
  void should_invalidateDetailAndPopularListOnlyAfterEventUpdateCommits() {
    eventService.getPublished(eventId);
    eventService.listPublished(NO_FILTER, POPULAR_PAGE);
    Cache detail = detailCache();
    Cache popular = popularCache();

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          eventService.update(eventId, eventRequest("Updated after commit"));
          assertThat(detail.get(eventId)).isNotNull();
          assertThat(popular.get(EventCatalogCacheService.POPULAR_KEY)).isNotNull();
        });

    assertThat(detail.get(eventId)).isNull();
    assertThat(popular.get(EventCatalogCacheService.POPULAR_KEY)).isNull();
    assertThat(eventService.getPublished(eventId).title()).isEqualTo("Updated after commit");
  }

  @Test
  void should_invalidateDetailAndPopularListAfterTicketTypeUpdateCommits() {
    eventService.getPublished(eventId);
    eventService.listPublished(NO_FILTER, POPULAR_PAGE);
    Cache detail = detailCache();
    Cache popular = popularCache();

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          ticketTypeService.update(
              ticketTypeId,
              new TicketTypeRequest("VIP", new BigDecimal("45.00"), 100, SALE_START, SALE_END, 4));
          assertThat(detail.get(eventId)).isNotNull();
          assertThat(popular.get(EventCatalogCacheService.POPULAR_KEY)).isNotNull();
        });

    assertThat(detail.get(eventId)).isNull();
    assertThat(popular.get(EventCatalogCacheService.POPULAR_KEY)).isNull();
    assertThat(eventService.getPublished(eventId).ticketTypes().getFirst().name()).isEqualTo("VIP");
  }

  @Test
  void should_cacheOnlyThePopularUnfilteredFirstPage() {
    PageResponse<EventSummaryResponse> initial =
        eventService.listPublished(NO_FILTER, POPULAR_PAGE);
    EventSummaryResponse firstEntry = initial.content().getFirst();
    String freshTitle = "Filtered fresh " + UUID.randomUUID();
    jdbcTemplate.update("UPDATE events SET title = ? WHERE id = ?", freshTitle, firstEntry.id());

    PageResponse<EventSummaryResponse> popular =
        eventService.listPublished(NO_FILTER, POPULAR_PAGE);
    assertThat(popular.content())
        .filteredOn(summary -> summary.id().equals(firstEntry.id()))
        .extracting(EventSummaryResponse::title)
        .containsExactly(firstEntry.title());

    PageResponse<EventSummaryResponse> filtered =
        eventService.listPublished(
            new EventFilter(freshTitle, null, null, null, null), POPULAR_PAGE);
    assertThat(filtered.content())
        .extracting(EventSummaryResponse::title)
        .containsExactly(freshTitle);
  }

  private Cache detailCache() {
    return cacheManager.getCache(EventCatalogCacheService.DETAIL_CACHE);
  }

  private Cache popularCache() {
    return cacheManager.getCache(EventCatalogCacheService.POPULAR_CACHE);
  }

  private EventRequest eventRequest(String title) {
    return new EventRequest(venueId, title, null, "MUSIC", START, END);
  }
}
