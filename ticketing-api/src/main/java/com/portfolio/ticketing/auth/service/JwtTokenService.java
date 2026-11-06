package com.portfolio.ticketing.auth.service;

import com.portfolio.ticketing.auth.api.TokenResponse;
import com.portfolio.ticketing.auth.config.AuthProperties;
import com.portfolio.ticketing.common.exception.AuthenticationFailedException;
import com.portfolio.ticketing.user.domain.User;
import com.portfolio.ticketing.user.service.UserService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {

  public static final String ISSUER = "ticketing-api";

  private final JwtEncoder jwtEncoder;
  private final JwtDecoder refreshJwtDecoder;
  private final RefreshTokenStore refreshTokenStore;
  private final UserService userService;
  private final AuthProperties properties;

  public JwtTokenService(
      JwtEncoder jwtEncoder,
      @Qualifier("refreshJwtDecoder") JwtDecoder refreshJwtDecoder,
      RefreshTokenStore refreshTokenStore,
      UserService userService,
      AuthProperties properties) {
    this.jwtEncoder = jwtEncoder;
    this.refreshJwtDecoder = refreshJwtDecoder;
    this.refreshTokenStore = refreshTokenStore;
    this.userService = userService;
    this.properties = properties;
  }

  public TokenResponse issueTokens(User user) {
    String jti = UUID.randomUUID().toString();
    Duration refreshTtl = Duration.ofDays(properties.getJwt().getRefreshTtlDays());
    String accessToken = createAccessToken(user);
    String refreshToken = createToken(user, "refresh", refreshTtl, jti);
    refreshTokenStore.save(jti, user.getId(), refreshTtl);
    return TokenResponse.bearer(
        accessToken,
        refreshToken,
        Duration.ofMinutes(properties.getJwt().getAccessTtlMinutes()).toSeconds());
  }

  public TokenResponse refresh(String refreshToken) {
    Jwt jwt = decodeRefreshToken(refreshToken);
    UUID userId = subject(jwt);
    if (!refreshTokenStore.isActive(jwt.getId(), userId)) {
      throw new AuthenticationFailedException("Refresh token is not active");
    }
    User user = userService.getById(userId);
    if (!user.isEnabled()) {
      throw new AuthenticationFailedException("User account is disabled");
    }
    return TokenResponse.bearer(
        createAccessToken(user),
        refreshToken,
        Duration.ofMinutes(properties.getJwt().getAccessTtlMinutes()).toSeconds());
  }

  public void logout(String refreshToken) {
    Jwt jwt = decodeRefreshToken(refreshToken);
    refreshTokenStore.revoke(jwt.getId());
  }

  private String createAccessToken(User user) {
    return createToken(
        user, "access", Duration.ofMinutes(properties.getJwt().getAccessTtlMinutes()), null);
  }

  private String createToken(User user, String type, Duration ttl, String jti) {
    Instant now = Instant.now();
    JwtClaimsSet.Builder claims =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(user.getId().toString())
            .issuedAt(now)
            .expiresAt(now.plus(ttl))
            .claim("role", user.getRole().name())
            .claim("type", type);
    if (jti != null) {
      claims.id(jti);
    }
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    return jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
  }

  private Jwt decodeRefreshToken(String token) {
    try {
      Jwt jwt = refreshJwtDecoder.decode(token);
      if (!"refresh".equals(jwt.getClaimAsString("type")) || jwt.getId() == null) {
        throw new AuthenticationFailedException("Invalid refresh token");
      }
      return jwt;
    } catch (JwtException | IllegalArgumentException exception) {
      throw new AuthenticationFailedException("Invalid refresh token", exception);
    }
  }

  private static UUID subject(Jwt jwt) {
    try {
      return UUID.fromString(jwt.getSubject());
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw new AuthenticationFailedException("Invalid refresh token", exception);
    }
  }
}
