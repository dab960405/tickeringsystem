package com.portfolio.ticketing.auth.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ticketing.security")
public class AuthProperties {

  @Valid private final Jwt jwt = new Jwt();

  public Jwt getJwt() {
    return jwt;
  }

  public static class Jwt {

    @NotBlank(message = "JWT_SECRET is required")
    private String secret;

    @Min(1)
    private long accessTtlMinutes = 15;

    @Min(1)
    private long refreshTtlDays = 7;

    public String getSecret() {
      return secret;
    }

    public void setSecret(String secret) {
      this.secret = secret;
    }

    public long getAccessTtlMinutes() {
      return accessTtlMinutes;
    }

    public void setAccessTtlMinutes(long accessTtlMinutes) {
      this.accessTtlMinutes = accessTtlMinutes;
    }

    public long getRefreshTtlDays() {
      return refreshTtlDays;
    }

    public void setRefreshTtlDays(long refreshTtlDays) {
      this.refreshTtlDays = refreshTtlDays;
    }

    @AssertTrue(message = "JWT_SECRET must be Base64 encoded and contain at least 32 bytes")
    public boolean isSecretStrong() {
      if (secret == null || secret.isBlank()) {
        return true;
      }
      try {
        return Base64.getDecoder().decode(secret).length >= 32;
      } catch (IllegalArgumentException exception) {
        return false;
      }
    }
  }
}
