package com.portfolio.ticketing.venue.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.ticketing.common.config.JpaAuditingConfig;
import com.portfolio.ticketing.support.AbstractIntegrationTest;
import com.portfolio.ticketing.venue.domain.Venue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import(JpaAuditingConfig.class)
class VenueRepositoryIT extends AbstractIntegrationTest {

  @Autowired private VenueRepository venueRepository;
  @Autowired private TestEntityManager entityManager;

  @Test
  void should_persistVenueWithAuditFields_when_saved() {
    Venue saved = venueRepository.saveAndFlush(new Venue("Arena", "Lima", "Main St 1", 500));
    entityManager.clear();

    Venue found = venueRepository.findById(saved.getId()).orElseThrow();

    assertThat(found)
        .satisfies(venue -> {
          assertThat(venue.getName()).isEqualTo("Arena");
          assertThat(venue.getCreatedAt()).isNotNull();
          assertThat(venue.getUpdatedAt()).isNotNull();
        });
  }
}
