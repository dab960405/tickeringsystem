package com.portfolio.ticketing.support;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
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
@ExtendWith(AbstractIntegrationTest.CiFailureAnnotation.class)
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

  static class CiFailureAnnotation implements TestWatcher {
    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
      StringWriter stackTrace = new StringWriter();
      cause.printStackTrace(new PrintWriter(stackTrace));
      String message = escape(context.getDisplayName() + "\n" + stackTrace);
      System.out.printf("::error file=%s::%s%n", context.getRequiredTestClass().getName(), message);
    }

    private static String escape(String value) {
      return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A");
    }
  }
}
