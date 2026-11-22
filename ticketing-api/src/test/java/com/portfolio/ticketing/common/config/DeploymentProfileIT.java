package com.portfolio.ticketing.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class DeploymentProfileIT {
  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-alpine");

  @ParameterizedTest
  @CsvSource({"'', 0", "prod, 0", "dev, 3"})
  void should_loadDemoAccountsOnly_when_devIsExplicitlySelected(String profile, int expectedUsers) {
    String schema = "profile_" + (profile.isEmpty() ? "default" : profile);
    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(
            AutoConfigurations.of(DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class))
        .withPropertyValues(
            "spring.profiles.active=" + profile,
            "spring.datasource.url=" + POSTGRES.getJdbcUrl() + "&currentSchema=" + schema,
            "spring.datasource.username=" + POSTGRES.getUsername(),
            "spring.datasource.password=" + POSTGRES.getPassword(),
            "spring.flyway.default-schema=" + schema)
        .run(context -> {
          assertThat(context).hasNotFailed();
          JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
          assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class))
              .isEqualTo(expectedUsers);
          assertThat(
                  jdbc.queryForObject(
                      "SELECT count(*) FROM flyway_schema_history WHERE script = 'R__seed_dev_data.sql'",
                      Integer.class))
              .isEqualTo(expectedUsers == 0 ? 0 : 1);
          if (expectedUsers > 0) {
            String hash =
                jdbc.queryForObject(
                    "SELECT password_hash FROM users WHERE email = 'admin@ticketing.dev'",
                    String.class);
            assertThat(new BCryptPasswordEncoder().matches("Password123", hash)).isTrue();
          }
        });
  }
}
