package com.portfolio.ticketing.common.ratelimit;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ticketing.rate-limits")
public class RateLimitProperties {

  private Map<String, Policy> scopes = new LinkedHashMap<>();

  public Map<String, Policy> getScopes() {
    return scopes;
  }

  public void setScopes(Map<String, Policy> scopes) {
    this.scopes = scopes;
  }

  public Policy policyFor(String scope) {
    Policy policy = scopes.get(scope);
    if (policy == null) {
      throw new IllegalArgumentException("No rate-limit policy configured for scope: " + scope);
    }
    return policy;
  }

  public static class Policy {

    private int maxAttempts = 5;
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
