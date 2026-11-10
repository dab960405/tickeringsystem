package com.portfolio.ticketing.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import com.portfolio.ticketing.common.exception.RateLimitExceededException;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class RedisRateLimiterTest {

  @Mock private StringRedisTemplate redisTemplate;

  private RedisRateLimiter rateLimiter;

  @BeforeEach
  void setUp() {
    RateLimitProperties properties = new RateLimitProperties();
    RateLimitProperties.Policy login = new RateLimitProperties.Policy();
    login.setMaxAttempts(2);
    login.setWindowSeconds(60);
    properties.setScopes(Map.of("login", login));
    rateLimiter = new RedisRateLimiter(redisTemplate, properties);
  }

  @Test
  void should_allowAndWarn_when_redisConnectionFails(CapturedOutput output) {
    doThrow(new RedisConnectionFailureException("offline"))
        .when(redisTemplate)
        .execute(any(RedisScript.class), anyList(), anyString());

    rateLimiter.check("login", "subject");

    org.assertj.core.api.Assertions.assertThat(output)
        .contains("WARN")
        .contains("Rate limiter unavailable for scope login; allowing request");
  }

  @Test
  void should_allowAndWarn_when_redisCommandTimesOut(CapturedOutput output) {
    doThrow(new QueryTimeoutException("timeout"))
        .when(redisTemplate)
        .execute(any(RedisScript.class), anyList(), anyString());

    rateLimiter.check("login", "subject");

    org.assertj.core.api.Assertions.assertThat(output)
        .contains("WARN")
        .contains("Rate limiter unavailable for scope login; allowing request");
  }

  @Test
  void should_reject_when_attemptsExceedConfiguredMaximum() {
    doReturn(3L).when(redisTemplate).execute(any(RedisScript.class), anyList(), anyString());

    assertThatThrownBy(() -> rateLimiter.check("login", "subject"))
        .isInstanceOf(RateLimitExceededException.class);
  }

  @Test
  void should_propagate_when_failureIsNotRedisDataAccessException() {
    doThrow(new IllegalStateException("unexpected"))
        .when(redisTemplate)
        .execute(any(RedisScript.class), anyList(), anyString());

    assertThatThrownBy(() -> rateLimiter.check("login", "subject"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("unexpected");
  }
}
