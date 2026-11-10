package com.portfolio.ticketing.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.ticketing.common.exception.TokenStoreUnavailableException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
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

  @Test
  void should_translateRedisFailure_when_saving() {
    UUID userId = UUID.fromString("0b5e6f1a-3c2d-4e5f-8a9b-1c2d3e4f5a6b");
    doThrow(new DataAccessResourceFailureException("offline"))
        .when(valueOperations)
        .set(eq(RefreshTokenStore.key("jti")), eq(userId.toString()), eq(Duration.ofMinutes(1)));

    assertThatThrownBy(() -> store.save("jti", userId, Duration.ofMinutes(1)))
        .isInstanceOf(TokenStoreUnavailableException.class);
  }

  @Test
  void should_translateRedisFailure_when_checkingActivity() {
    when(valueOperations.get(RefreshTokenStore.key("jti")))
        .thenThrow(new DataAccessResourceFailureException("offline"));

    assertThatThrownBy(() -> store.isActive("jti", UUID.randomUUID()))
        .isInstanceOf(TokenStoreUnavailableException.class);
  }

  @Test
  void should_translateRedisFailure_when_revoking() {
    doThrow(new DataAccessResourceFailureException("offline"))
        .when(redisTemplate)
        .delete(RefreshTokenStore.key("jti"));

    assertThatThrownBy(() -> store.revoke("jti"))
        .isInstanceOf(TokenStoreUnavailableException.class);
  }

  @Test
  void should_notTranslateNonRedisFailure() {
    when(valueOperations.get(RefreshTokenStore.key("jti")))
        .thenThrow(new IllegalStateException("unexpected"));

    assertThatThrownBy(() -> store.isActive("jti", UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("unexpected");
  }
}
