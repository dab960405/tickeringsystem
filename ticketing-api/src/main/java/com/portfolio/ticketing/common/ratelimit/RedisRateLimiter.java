package com.portfolio.ticketing.common.ratelimit;

import com.portfolio.ticketing.common.exception.RateLimitExceededException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisRateLimiter implements RateLimiter {

  private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
  private static final String KEY_PREFIX = "rate:";
  private static final DefaultRedisScript<Long> INCREMENT_AND_EXPIRE_SCRIPT =
      new DefaultRedisScript<>(
          """
          local attempts = redis.call('INCR', KEYS[1])
          local ttl = redis.call('TTL', KEYS[1])
          if attempts == 1 or ttl == -1 then
            redis.call('EXPIRE', KEYS[1], ARGV[1])
          end
          return attempts
          """,
          Long.class);

  private final StringRedisTemplate redisTemplate;
  private final RateLimitProperties properties;

  public RedisRateLimiter(StringRedisTemplate redisTemplate, RateLimitProperties properties) {
    this.redisTemplate = redisTemplate;
    this.properties = properties;
  }

  @Override
  public void check(String scope, String subject) {
    RateLimitProperties.Policy policy = properties.policyFor(scope);
    String key = KEY_PREFIX + scope + ":" + subject;
    try {
      Long attempts =
          redisTemplate.execute(
              INCREMENT_AND_EXPIRE_SCRIPT, List.of(key), Long.toString(policy.getWindowSeconds()));
      if (attempts != null && attempts > policy.getMaxAttempts()) {
        throw new RateLimitExceededException(
            "Too many " + scope + " attempts, please try again later");
      }
    } catch (DataAccessException exception) {
      log.warn("Rate limiter unavailable for scope {}; allowing request", scope);
    }
  }
}
