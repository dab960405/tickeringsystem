package com.portfolio.ticketing.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
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

  protected static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4.12-alpine")).withExposedPorts(6379);

  static {
    POSTGRES.start();
    REDIS.start();
  }

  @DynamicPropertySource
  static void redisProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
  }

  protected static void stopRedis() {
    REDIS.getDockerClient().stopContainerCmd(REDIS.getContainerId()).exec();
  }

  protected static void startRedis() {
    REDIS.getDockerClient().startContainerCmd(REDIS.getContainerId()).exec();
  }
}
