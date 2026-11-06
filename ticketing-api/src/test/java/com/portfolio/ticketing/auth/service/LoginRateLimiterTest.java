package com.portfolio.ticketing.auth.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.ticketing.auth.config.AuthProperties;
import com.portfolio.ticketing.common.exception.RateLimitExceededException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class LoginRateLimiterTest {

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOperations;
  private LoginRateLimiter rateLimiter;

  @BeforeEach
  void setUp() {
    AuthProperties properties = new AuthProperties();
    properties.getLogin().setMaxAttempts(2);
    properties.getLogin().setWindowSeconds(60);
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    rateLimiter = new LoginRateLimiter(redisTemplate, properties);
  }

  @Test
  void should_setFixedWindowOnFirstAttempt() {
    when(valueOperations.increment(anyString())).thenReturn(1L);

    rateLimiter.check("127.0.0.1", "User@Example.com");

    verify(redisTemplate).expire("rate:login:127.0.0.1:user@example.com", Duration.ofSeconds(60));
  }

  @Test
  void should_rejectAttemptsAboveConfiguredLimit() {
    when(valueOperations.increment(anyString())).thenReturn(3L);

    assertThatThrownBy(() -> rateLimiter.check("127.0.0.1", "user@example.com"))
        .isInstanceOf(RateLimitExceededException.class);
  }

  @Test
  void should_allowAttemptsAtConfiguredLimit() {
    when(valueOperations.increment(anyString())).thenReturn(2L);

    rateLimiter.check("127.0.0.1", "user@example.com");

    verify(valueOperations).increment(eq("rate:login:127.0.0.1:user@example.com"));
  }
}
