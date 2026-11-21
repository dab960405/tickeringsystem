package com.portfolio.ticketing.common.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.SerializationException;

class RedisCacheConfigTest {

  private final RedisCacheConfig config =
      new RedisCacheConfig(
          mock(RedisConnectionFactory.class), Duration.ofMinutes(5), Duration.ofSeconds(60));
  private final Cache cache = mock(Cache.class);

  @Test
  void should_continueWithoutCacheWhenSerializationFails() {
    CacheErrorHandler errorHandler = config.errorHandler();
    SerializationException exception = new SerializationException("Cannot serialize catalog");

    assertThatCode(() -> errorHandler.handleCacheGetError(exception, cache, "event-id"))
        .doesNotThrowAnyException();
    assertThatCode(() -> errorHandler.handleCachePutError(exception, cache, "event-id", "value"))
        .doesNotThrowAnyException();
    assertThatCode(() -> errorHandler.handleCacheEvictError(exception, cache, "event-id"))
        .doesNotThrowAnyException();
    assertThatCode(() -> errorHandler.handleCacheClearError(exception, cache))
        .doesNotThrowAnyException();
  }
}
