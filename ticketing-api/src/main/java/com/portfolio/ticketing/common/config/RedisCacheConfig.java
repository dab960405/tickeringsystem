package com.portfolio.ticketing.common.config;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

@Configuration
@EnableCaching
public class RedisCacheConfig implements CachingConfigurer {

  private static final Logger log = LoggerFactory.getLogger(RedisCacheConfig.class);

  @Bean
  @Override
  public CacheManager cacheManager(
      RedisConnectionFactory connectionFactory,
      @Value("${ticketing.cache.ticket-types.ttl:5m}") Duration ticketTypesTtl) {
    RedisCacheConfiguration defaults =
        RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(ticketTypesTtl)
            .disableCachingNullValues()
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(
                    new GenericJackson2JsonRedisSerializer()));

    return RedisCacheManager.builder(connectionFactory)
        .cacheDefaults(defaults)
        .transactionAware()
        .build();
  }

  @Bean
  @Override
  public CacheErrorHandler errorHandler() {
    return new CacheErrorHandler() {
      @Override
      public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        handleRedisFailure("GET", exception, cache, key);
      }

      @Override
      public void handleCachePutError(
          RuntimeException exception, Cache cache, Object key, Object value) {
        handleRedisFailure("PUT", exception, cache, key);
      }

      @Override
      public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        handleRedisFailure("EVICT", exception, cache, key);
      }

      @Override
      public void handleCacheClearError(RuntimeException exception, Cache cache) {
        handleRedisFailure("CLEAR", exception, cache, "*");
      }
    };
  }

  private static void handleRedisFailure(
      String operation, RuntimeException exception, Cache cache, Object key) {
    if (exception instanceof DataAccessException) {
      log.warn(
          "Redis cache {} failed for cache '{}' and key '{}'; continuing without cache",
          operation,
          cache.getName(),
          key,
          exception);
      return;
    }
    throw exception;
  }
}
