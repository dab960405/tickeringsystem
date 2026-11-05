package com.portfolio.ticketing.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared base for integration tests. The container is started once per JVM (singleton pattern) so
 * every test class and Spring context reuses the same PostgreSQL instance. The "test" profile keeps
 * the dev seed data out of integration tests.
 */
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

  @ServiceConnection
  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine")).withReuse(true);

  static {
    POSTGRES.start();
  }
}
