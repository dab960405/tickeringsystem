package com.portfolio.ticketing.reservation.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class ReservationRedisProjectionServiceTest {

  private static final Instant NOW = Instant.parse("2030-01-01T12:00:00Z");

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOperations;

  private ReservationRedisProjectionService service;

  @BeforeEach
  void setUp() {
    service =
        new ReservationRedisProjectionService(
            redisTemplate,
            new ObjectMapper().registerModule(new JavaTimeModule()),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void should_notFailBusinessFlow_when_redisWriteIsUnavailable() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    doThrow(new RedisConnectionFailureException("redis unavailable"))
        .when(valueOperations)
        .set(anyString(), anyString(), any(Duration.class));

    assertThatCode(() -> service.writeActiveAfterCommit(UUID.randomUUID(), NOW.plusSeconds(600)))
        .doesNotThrowAnyException();
  }

  @Test
  void should_notFailBusinessFlow_when_redisDeleteIsUnavailable() {
    when(redisTemplate.delete(anyString()))
        .thenThrow(new RedisConnectionFailureException("redis unavailable"));

    assertThatCode(() -> service.deleteAfterCommit(UUID.randomUUID())).doesNotThrowAnyException();
  }
}
