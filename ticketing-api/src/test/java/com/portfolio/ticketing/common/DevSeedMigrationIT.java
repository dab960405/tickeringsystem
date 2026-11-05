package com.portfolio.ticketing.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.ticketing.support.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/** Applies the dev seed in an isolated schema so the shared test schema stays empty. */
class DevSeedMigrationIT extends AbstractIntegrationTest {

  private static final String SCHEMA = "dev_seed_check";

  @Test
  void should_loadDemoData_when_devSeedLocationIsEnabled() throws SQLException {
    Flyway flyway =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .schemas(SCHEMA)
            .locations("classpath:db/migration", "classpath:db/seed")
            .load();

    flyway.migrate();

    assertThat(count("users")).isEqualTo(3);
    assertThat(count("venues")).isEqualTo(2);
    assertThat(count("events WHERE status = 'PUBLISHED'")).isEqualTo(1);
    assertThat(count("ticket_types")).isEqualTo(3);
  }

  private static long count(String tableAndFilter) throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery("SELECT count(*) FROM " + SCHEMA + "." + tableAndFilter)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
