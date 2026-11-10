package com.portfolio.ticketing.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.portfolio.ticketing.common.exception.RateLimitExceededException;
import com.portfolio.ticketing.support.AbstractIntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest(
    properties = {
      "ticketing.rate-limits.scopes.login.max-attempts=1",
      "ticketing.rate-limits.scopes.login.window-seconds=1"
    })
class RedisRateLimiterIT extends AbstractIntegrationTest {

  @Autowired private RedisRateLimiter rateLimiter;

  @Autowired private StringRedisTemplate redisTemplate;

  @Test
  void should_repairMissingTtl_when_keyAlreadyExists() {
    String subject = "ttl-repair-" + UUID.randomUUID();
    String key = "rate:login:" + subject;
    redisTemplate.opsForValue().set(key, "0");

    rateLimiter.check("login", subject);

    assertThat(redisTemplate.getExpire(key)).isPositive();
  }

  @Test
  void should_resetCounterAfterConfiguredWindow() {
    String subject = "window-reset-" + UUID.randomUUID();
    String key = "rate:login:" + subject;

    rateLimiter.check("login", subject);
    assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("1");

    assertThatThrownBy(() -> rateLimiter.check("login", subject))
        .isInstanceOf(RateLimitExceededException.class);

    await()
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(100))
        .ignoreExceptions()
        .untilAsserted(() -> {
          rateLimiter.check("login", subject);
          assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("1");
        });
  }
}
