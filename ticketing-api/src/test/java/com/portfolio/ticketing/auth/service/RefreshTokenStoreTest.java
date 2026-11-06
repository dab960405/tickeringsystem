package com.portfolio.ticketing.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class RefreshTokenStoreTest {

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOperations;
  private RefreshTokenStore store;

  @BeforeEach
  void setUp() {
    lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    store = new RefreshTokenStore(redisTemplate);
  }

  @Test
  void should_storeOnlyHashedRefreshIdWithTtl() {
    UUID userId = UUID.randomUUID();

    store.save("raw-jti", userId, Duration.ofDays(7));

    ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
    verify(valueOperations).set(key.capture(), eq(userId.toString()), eq(Duration.ofDays(7)));
    assertThat(key.getValue()).startsWith("auth:refresh:").doesNotContain("raw-jti");
  }

  @Test
  void should_checkUserBoundToRefreshId() {
    UUID userId = UUID.randomUUID();
    when(valueOperations.get(RefreshTokenStore.key("jti"))).thenReturn(userId.toString());

    assertThat(store.isActive("jti", userId)).isTrue();
    assertThat(store.isActive("jti", UUID.randomUUID())).isFalse();
  }

  @Test
  void should_deleteHashedRefreshId_when_revoking() {
    store.revoke("jti");

    verify(redisTemplate).delete(RefreshTokenStore.key("jti"));
  }
}
