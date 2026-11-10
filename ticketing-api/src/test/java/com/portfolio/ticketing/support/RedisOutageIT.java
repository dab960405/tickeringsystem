package com.portfolio.ticketing.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration-test fixture with isolated PostgreSQL and Redis containers.
 *
 * <p>This class deliberately does not extend {@link AbstractIntegrationTest}: its Redis instance
 * must remain independent from the JVM-shared Redis container used by the other integration tests.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class RedisOutageIT {

  @Container
  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.11-alpine"));

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4.12-alpine")).withExposedPorts(6379);

  @Autowired private RedisConnectionFactory redisConnectionFactory;

  @Autowired private StringRedisTemplate redisTemplate;

  @DynamicPropertySource
  static void redisProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("spring.data.redis.timeout", () -> "500ms");
    registry.add("spring.data.redis.connect-timeout", () -> "500ms");
  }

  @Test
  void should_useDedicatedRedisContainerInSpringContext() {
    assertThat(redisConnectionFactory).isInstanceOf(LettuceConnectionFactory.class);
    LettuceConnectionFactory lettuceConnectionFactory =
        (LettuceConnectionFactory) redisConnectionFactory;

    assertThat(lettuceConnectionFactory.getPort())
        .isEqualTo(REDIS.getMappedPort(6379))
        .isNotEqualTo(AbstractIntegrationTest.REDIS.getMappedPort(6379));
  }

  @Test
  void should_configureShortCommandTimeoutOnLettuceClient() {
    LettuceConnectionFactory lettuceConnectionFactory =
        (LettuceConnectionFactory) redisConnectionFactory;

    assertThat(lettuceConnectionFactory.getClientConfiguration().getCommandTimeout())
        .isEqualTo(Duration.ofMillis(500));
  }

  @Test
  void should_roundTripValueThroughContextRedis() {
    String key = "ticketing:redis-outage-it:" + UUID.randomUUID();
    try {
      redisTemplate.opsForValue().set(key, "connected");
      assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("connected");
    } finally {
      redisTemplate.delete(key);
    }
  }
}
