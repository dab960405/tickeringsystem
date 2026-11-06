package com.portfolio.ticketing.auth.service;

import com.portfolio.ticketing.auth.config.AuthProperties;
import com.portfolio.ticketing.common.exception.RateLimitExceededException;
import com.portfolio.ticketing.user.service.UserService;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class LoginRateLimiter {

  private final StringRedisTemplate redisTemplate;
  private final AuthProperties properties;

  public LoginRateLimiter(StringRedisTemplate redisTemplate, AuthProperties properties) {
    this.redisTemplate = redisTemplate;
    this.properties = properties;
  }

  public void check(String remoteAddress, String email) {
    String key =
        "rate:login:"
            + (remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress)
            + ":"
            + UserService.normalizeEmail(email);
    Long attempts = redisTemplate.opsForValue().increment(key);
    if (attempts != null && attempts == 1L) {
      redisTemplate.expire(key, Duration.ofSeconds(properties.getLogin().getWindowSeconds()));
    }
    if (attempts != null && attempts > properties.getLogin().getMaxAttempts()) {
      throw new RateLimitExceededException("Too many login attempts, please try again later");
    }
  }
}
