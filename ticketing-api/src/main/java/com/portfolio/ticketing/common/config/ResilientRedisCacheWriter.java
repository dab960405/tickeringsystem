package com.portfolio.ticketing.common.config;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCacheWriter;

/** Also degrades cache operations invoked by transaction synchronizations after commit. */
final class ResilientRedisCacheWriter implements RedisCacheWriter {

  private static final Logger log = LoggerFactory.getLogger(ResilientRedisCacheWriter.class);

  private final RedisCacheWriter delegate;

  ResilientRedisCacheWriter(RedisCacheWriter delegate) {
    this.delegate = delegate;
  }

  @Override
  public byte[] get(String name, byte[] key) {
    return fallback("GET", () -> delegate.get(name, key), null);
  }

  @Override
  public byte[] get(String name, byte[] key, Duration ttl) {
    return fallback("GET", () -> delegate.get(name, key, ttl), null);
  }

  @Override
  public CompletableFuture<byte[]> retrieve(String name, byte[] key, Duration ttl) {
    return fallback(
        "RETRIEVE",
        () ->
            delegate
                .retrieve(name, key, ttl)
                .exceptionally(exception -> {
                  log.warn("Redis cache RETRIEVE failed; continuing without cache", exception);
                  return null;
                }),
        CompletableFuture.completedFuture(null));
  }

  @Override
  public void put(String name, byte[] key, byte[] value, Duration ttl) {
    run("PUT", () -> delegate.put(name, key, value, ttl));
  }

  @Override
  public CompletableFuture<Void> store(String name, byte[] key, byte[] value, Duration ttl) {
    return fallback(
        "STORE",
        () ->
            delegate
                .store(name, key, value, ttl)
                .exceptionally(exception -> {
                  log.warn("Redis cache STORE failed; continuing without cache", exception);
                  return null;
                }),
        CompletableFuture.completedFuture(null));
  }

  @Override
  public byte[] putIfAbsent(String name, byte[] key, byte[] value, Duration ttl) {
    return fallback("PUT_IF_ABSENT", () -> delegate.putIfAbsent(name, key, value, ttl), null);
  }

  @Override
  public void remove(String name, byte[] key) {
    run("REMOVE", () -> delegate.remove(name, key));
  }

  @Override
  public void clean(String name, byte[] pattern) {
    run("CLEAN", () -> delegate.clean(name, pattern));
  }

  @Override
  public void clearStatistics(String name) {
    run("CLEAR_STATISTICS", () -> delegate.clearStatistics(name));
  }

  @Override
  public RedisCacheWriter withStatisticsCollector(CacheStatisticsCollector collector) {
    return new ResilientRedisCacheWriter(delegate.withStatisticsCollector(collector));
  }

  @Override
  public CacheStatistics getCacheStatistics(String cacheName) {
    return delegate.getCacheStatistics(cacheName);
  }

  private static void run(String operation, Runnable action) {
    try {
      action.run();
    } catch (DataAccessException exception) {
      log.warn("Redis cache {} failed; continuing without cache", operation, exception);
    }
  }

  private static <T> T fallback(String operation, Supplier<T> action, T fallback) {
    try {
      return action.get();
    } catch (DataAccessException exception) {
      log.warn("Redis cache {} failed; continuing without cache", operation, exception);
      return fallback;
    }
  }
}
