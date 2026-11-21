package com.portfolio.ticketing.common.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ticketing.rate-limits")
public class RateLimitProperties {

  @Valid
  @NotEmpty(message = "At least one rate-limit scope must be configured")
  private Map<String, Policy> scopes = new LinkedHashMap<>();

  public Map<String, Policy> getScopes() {
    return scopes;
  }

  public void setScopes(Map<String, Policy> scopes) {
    this.scopes = scopes;
  }

  public static final List<String> REQUIRED_SCOPES = List.of("login", "reservations", "orders");

  @AssertTrue(
      message = "Rate-limit policies 'login', 'reservations' and 'orders' must be configured")
  public boolean isRequiredPoliciesConfigured() {
    return scopes != null && scopes.keySet().containsAll(REQUIRED_SCOPES);
  }

  public Policy policyFor(String scope) {
    Policy policy = scopes.get(scope);
    if (policy == null) {
      throw new IllegalArgumentException("No rate-limit policy configured for scope: " + scope);
    }
    return policy;
  }

  public static class Policy {

    @Min(value = 1, message = "max-attempts must be greater than or equal to 1")
    private int maxAttempts = 5;

    @Min(value = 1, message = "window-seconds must be greater than or equal to 1")
    private long windowSeconds = 60;

    public int getMaxAttempts() {
      return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
      this.maxAttempts = maxAttempts;
    }

    public long getWindowSeconds() {
      return windowSeconds;
    }

    public void setWindowSeconds(long windowSeconds) {
      this.windowSeconds = windowSeconds;
    }
  }
}
