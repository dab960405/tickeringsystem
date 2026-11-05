package com.portfolio.ticketing.event.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.ticketing.common.config.JpaAuditingConfig;
import com.portfolio.ticketing.event.domain.Event;
import com.portfolio.ticketing.event.domain.EventStatus;
import com.portfolio.ticketing.support.AbstractIntegrationTest;
import com.portfolio.ticketing.venue.domain.Venue;
import com.portfolio.ticketing.venue.repository.VenueRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@Import(JpaAuditingConfig.class)
class EventRepositoryIT extends AbstractIntegrationTest {

  @Autowired private EventRepository eventRepository;
  @Autowired private VenueRepository venueRepository;
  @Autowired private TestEntityManager entityManager;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID insertOrganizer() {
    return jdbcTemplate.queryForObject(
        "INSERT INTO users (email, password_hash, full_name, role)"
            + " VALUES (?, 'hash', 'Organizer', 'ORGANIZER') RETURNING id",
        UUID.class,
        "organizer-" + UUID.randomUUID() + "@example.com");
  }

  @Test
  void should_persistPublishedStatus_when_eventIsPublishedAndSaved() {
    Venue venue = venueRepository.save(new Venue("Arena", "Lima", "Main St 1", 500));
    Event event =
        new Event(
            insertOrganizer(),
            venue,
            "Show",
            "Desc",
            "MUSIC",
            Instant.parse("2030-01-10T20:00:00Z"),
            Instant.parse("2030-01-10T23:00:00Z"));
    event.publish();
    UUID id = eventRepository.saveAndFlush(event).getId();
    entityManager.clear();

    Event found = eventRepository.findById(id).orElseThrow();

    assertThat(found.getStatus()).isEqualTo(EventStatus.PUBLISHED);
  }
}
