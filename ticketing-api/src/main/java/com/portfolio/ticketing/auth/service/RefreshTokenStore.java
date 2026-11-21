package com.portfolio.ticketing.auth.service;

import com.portfolio.ticketing.common.exception.TokenStoreUnavailableException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RefreshTokenStore {

  private static final String KEY_PREFIX = "auth:refresh:";

  private final StringRedisTemplate redisTemplate;

  public RefreshTokenStore(StringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  public void save(String jti, UUID userId, Duration ttl) {
    try {
      redisTemplate.opsForValue().set(key(jti), userId.toString(), ttl);
    } catch (DataAccessException exception) {
      throw new TokenStoreUnavailableException(exception);
    }
  }

  public boolean isActive(String jti, UUID userId) {
    try {
      return userId.toString().equals(redisTemplate.opsForValue().get(key(jti)));
    } catch (DataAccessException exception) {
      throw new TokenStoreUnavailableException(exception);
    }
  }

  public void revoke(String jti) {
    try {
      redisTemplate.delete(key(jti));
    } catch (DataAccessException exception) {
      throw new TokenStoreUnavailableException(exception);
    }
  }

  static String key(String jti) {
    return KEY_PREFIX + sha256(jti);
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }
}
