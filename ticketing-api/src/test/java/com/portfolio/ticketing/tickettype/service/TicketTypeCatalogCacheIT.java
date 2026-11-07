package com.portfolio.ticketing.tickettype.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.tickettype.api.TicketTypeCatalogEntry;
import com.portfolio.ticketing.support.AbstractIntegrationTest;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import com.portfolio.ticketing.venue.domain.Venue;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "ticketing.cache.ticket-types.ttl=2s")
class TicketTypeCatalogCacheIT extends AbstractIntegrationTest {

  private static final Instant SALE_START = Instant.parse("2030-01-01T00:00:00Z");
  private static final Instant SALE_END = Instant.parse("2030-01-10T00:00:00Z");

  @Autowired private TicketTypeCatalogCacheService catalogCacheService;
  @Autowired private EntityManager entityManager;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID eventId;
  private UUID ticketTypeId;

  @BeforeEach
  void setUp() {
    UUID organizerId =
        jdbcTemplate.queryForObject(
            "INSERT INTO users (email, password_hash, full_name, role)"
                + " VALUES (?, 'hash', 'Organizer', 'ORGANIZER') RETURNING id",
            UUID.class,
            "cache-test-" + UUID.randomUUID() + "@example.com");

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              Venue venue =
                  new Venue("Cache Arena", "Lima", "Main St 1", 500);
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
              eventId = event.getId();
              ticketTypeId = ticketType.getId();
            });
  }

  @Test
  void should_serveCachedCatalogAndReloadAfterTtlExpires() throws InterruptedException {
    assertThat(catalogCacheService.listForEvent(eventId))
        .extracting(TicketTypeCatalogEntry::name)
        .containsExactly("General");

    jdbcTemplate.update("UPDATE ticket_types SET name = 'Updated' WHERE id = ?", ticketTypeId);

    assertThat(catalogCacheService.listForEvent(eventId))
        .extracting(TicketTypeCatalogEntry::name)
        .containsExactly("General");

    Thread.sleep(2500);

    assertThat(catalogCacheService.listForEvent(eventId))
        .extracting(TicketTypeCatalogEntry::name)
        .containsExactly("Updated");
  }
}
