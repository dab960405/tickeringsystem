package com.portfolio.ticketing.common.config;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.portfolio.ticketing.tickettype.api.TicketTypeCatalogEntry;
import java.time.Duration;
import java.util.List;
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
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.SerializationException;

@Configuration
@EnableCaching
public class RedisCacheConfig implements CachingConfigurer {

  private static final Logger log = LoggerFactory.getLogger(RedisCacheConfig.class);

  private final RedisConnectionFactory connectionFactory;
  private final Duration ticketTypesTtl;

  public RedisCacheConfig(
      RedisConnectionFactory connectionFactory,
      @Value("${ticketing.cache.ticket-types.ttl:5m}") Duration ticketTypesTtl) {
    this.connectionFactory = connectionFactory;
    this.ticketTypesTtl = ticketTypesTtl;
  }

  @Bean
  @Override
  public CacheManager cacheManager() {
    ObjectMapper objectMapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    JavaType catalogType =
        objectMapper
            .getTypeFactory()
            .constructCollectionType(List.class, TicketTypeCatalogEntry.class);
    Jackson2JsonRedisSerializer<List<TicketTypeCatalogEntry>> serializer =
        new Jackson2JsonRedisSerializer<>(objectMapper, catalogType);

    RedisCacheConfiguration defaults =
        RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(ticketTypesTtl)
            .disableCachingNullValues()
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair.fromSerializer(serializer));

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
    if (exception instanceof DataAccessException || exception instanceof SerializationException) {
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
