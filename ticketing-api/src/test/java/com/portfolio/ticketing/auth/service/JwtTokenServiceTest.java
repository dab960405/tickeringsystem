package com.portfolio.ticketing.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.ticketing.auth.api.TokenResponse;
import com.portfolio.ticketing.auth.config.AuthProperties;
import com.portfolio.ticketing.common.exception.AuthenticationFailedException;
import com.portfolio.ticketing.user.domain.User;
import com.portfolio.ticketing.user.domain.UserRole;
import com.portfolio.ticketing.user.service.UserService;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class JwtTokenServiceTest {

  private static final UUID USER_ID = UUID.fromString("0b5e6f1a-3c2d-4e5f-8a9b-1c2d3e4f5a6b");

  @Mock private JwtEncoder jwtEncoder;
  @Mock private JwtDecoder refreshJwtDecoder;
  @Mock private RefreshTokenStore refreshTokenStore;
  @Mock private UserService userService;

  private JwtTokenService tokenService;
  private User user;

  @BeforeEach
  void setUp() {
    AuthProperties properties = new AuthProperties();
    properties.getJwt().setAccessTtlMinutes(15);
    properties.getJwt().setRefreshTtlDays(7);
    tokenService =
        new JwtTokenService(
            jwtEncoder, refreshJwtDecoder, refreshTokenStore, userService, properties);
    user = new User("user@example.com", "hash", "User", UserRole.USER);
    ReflectionTestUtils.setField(user, "id", USER_ID);
    lenient()
        .when(jwtEncoder.encode(any(JwtEncoderParameters.class)))
        .thenAnswer(invocation -> {
          JwtClaimsSet claims = invocation.getArgument(0, JwtEncoderParameters.class).getClaims();
          return new Jwt(
              "encoded-" + claims.getClaimAsString("type"),
              claims.getIssuedAt(),
              claims.getExpiresAt(),
              Map.of("alg", "HS256"),
              claims.getClaims());
        });
  }

  @Test
  void should_issueAccessAndRefreshTokensAndStoreRefreshId() {
    TokenResponse response = tokenService.issueTokens(user);

    assertThat(response.accessToken()).isEqualTo("encoded-access");
    assertThat(response.refreshToken()).isEqualTo("encoded-refresh");
    assertThat(response.tokenType()).isEqualTo("Bearer");
    assertThat(response.expiresIn()).isEqualTo(900);
    verify(refreshTokenStore).save(any(String.class), eq(USER_ID), eq(Duration.ofDays(7)));
  }

  @Test
  void should_returnNewAccessToken_when_refreshTokenIsActive() {
    Jwt jwt = refreshJwt("jti-1", "refresh");
    when(refreshJwtDecoder.decode("refresh-token")).thenReturn(jwt);
    when(refreshTokenStore.isActive("jti-1", USER_ID)).thenReturn(true);
    when(userService.getById(USER_ID)).thenReturn(user);

    TokenResponse response = tokenService.refresh("refresh-token");

    assertThat(response.accessToken()).isEqualTo("encoded-access");
    assertThat(response.refreshToken()).isEqualTo("refresh-token");
  }

  @Test
  void should_rejectRefresh_when_tokenIsRevoked() {
    when(refreshJwtDecoder.decode("refresh-token")).thenReturn(refreshJwt("jti-1", "refresh"));
    when(refreshTokenStore.isActive("jti-1", USER_ID)).thenReturn(false);

    assertThatThrownBy(() -> tokenService.refresh("refresh-token"))
        .isInstanceOf(AuthenticationFailedException.class);
  }

  @Test
  void should_rejectAccessToken_when_usedForRefresh() {
    when(refreshJwtDecoder.decode("access-token")).thenReturn(refreshJwt("jti-1", "access"));

    assertThatThrownBy(() -> tokenService.refresh("access-token"))
        .isInstanceOf(AuthenticationFailedException.class);
  }

  @Test
  void should_revokeRefreshId_when_loggingOut() {
    when(refreshJwtDecoder.decode("refresh-token")).thenReturn(refreshJwt("jti-1", "refresh"));

    tokenService.logout("refresh-token");

    verify(refreshTokenStore).revoke("jti-1");
  }

  private static Jwt refreshJwt(String jti, String type) {
    Instant now = Instant.now();
    return new Jwt(
        "token",
        now,
        now.plusSeconds(3600),
        Map.of("alg", "HS256"),
        Map.of("sub", USER_ID.toString(), "type", type, "jti", jti, "iss", JwtTokenService.ISSUER));
  }
}
