package com.portfolio.ticketing.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.ticketing.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
class FlywayMigrationIT extends AbstractIntegrationTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void should_createAllTables_when_migrationsRun() {
    List<String> tables =
        jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
            String.class);

    assertThat(tables)
        .contains(
            "users",
            "venues",
            "events",
            "ticket_types",
            "reservations",
            "orders",
            "order_items",
            "tickets",
            "payments",
            "outbox_events");
  }

  @Test
  void should_rejectEmail_when_notLowercase() {
    assertThatThrownBy(() ->
            jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, full_name, role)"
                    + " VALUES ('Upper@Example.com', 'hash', 'Name', 'USER')"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void should_rejectEvent_when_endsBeforeStart() {
    assertThatThrownBy(() ->
            jdbcTemplate.update(
                """
                WITH u AS (
                  INSERT INTO users (email, password_hash, full_name, role)
                  VALUES ('organizer@example.com', 'hash', 'Org', 'ORGANIZER') RETURNING id),
                v AS (
                  INSERT INTO venues (name, city, address, capacity)
                  VALUES ('Arena', 'Lima', 'Main St 1', 100) RETURNING id)
                INSERT INTO events (organizer_id, venue_id, title, category, starts_at, ends_at)
                SELECT u.id, v.id, 'Show', 'MUSIC', now() + interval '2 days', now() + interval '1 day'
                FROM u, v
                """))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
