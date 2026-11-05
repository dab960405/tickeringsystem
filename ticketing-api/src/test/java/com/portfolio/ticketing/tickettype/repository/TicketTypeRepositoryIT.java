package com.portfolio.ticketing.tickettype.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.ticketing.common.config.JpaAuditingConfig;
import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.support.AbstractIntegrationTest;
import com.portfolio.ticketing.tickettype.domain.TicketType;
import com.portfolio.ticketing.venue.domain.Venue;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@Import(JpaAuditingConfig.class)
class TicketTypeRepositoryIT extends AbstractIntegrationTest {

  private static final Instant SALE_START = Instant.parse("2030-01-01T00:00:00Z");
  private static final Instant SALE_END = Instant.parse("2030-01-10T00:00:00Z");

  @Autowired private TicketTypeRepository ticketTypeRepository;
  @Autowired private TestEntityManager entityManager;
  @Autowired private JdbcTemplate jdbcTemplate;

  private Event event;

  @BeforeEach
  void setUp() {
    UUID organizerId =
        jdbcTemplate.queryForObject(
            "INSERT INTO users (email, password_hash, full_name, role)"
                + " VALUES (?, 'hash', 'Organizer', 'ORGANIZER') RETURNING id",
            UUID.class,
            "organizer-" + UUID.randomUUID() + "@example.com");
    Venue venue = entityManager.persist(new Venue("Arena", "Lima", "Main St 1", 500));
    event =
        entityManager.persist(
            new Event(
                organizerId,
                venue,
                "Show",
                null,
                "MUSIC",
                Instant.parse("2030-01-10T20:00:00Z"),
                Instant.parse("2030-01-10T23:00:00Z")));
  }

  private TicketType newTicketType(String name, String price) {
    return new TicketType(event, name, new BigDecimal(price), 100, SALE_START, SALE_END, 4);
  }

  @Test
  void should_returnTicketTypesSortedByPrice_when_findingByEvent() {
    ticketTypeRepository.saveAll(
        List.of(newTicketType("VIP", "120.00"), newTicketType("General", "40.00")));
    entityManager.flush();
    entityManager.clear();

    List<TicketType> found = ticketTypeRepository.findByEventIdOrderByPriceAsc(event.getId());

    assertThat(found).extracting(TicketType::getName).containsExactly("General", "VIP");
  }

  @Test
  void should_detectDuplicateName_when_caseDiffers() {
    ticketTypeRepository.saveAndFlush(newTicketType("VIP", "120.00"));

    assertThat(ticketTypeRepository.existsByEventIdAndNameIgnoreCase(event.getId(), "vip"))
        .isTrue();
  }

  @Test
  void should_incrementVersion_when_updated() {
    TicketType saved = ticketTypeRepository.saveAndFlush(newTicketType("VIP", "120.00"));
    long initialVersion = saved.getVersion();

    saved.update("VIP", new BigDecimal("130.00"), 100, SALE_START, SALE_END, 4);
    ticketTypeRepository.saveAndFlush(saved);

    assertThat(saved.getVersion()).isGreaterThan(initialVersion);
  }
}
