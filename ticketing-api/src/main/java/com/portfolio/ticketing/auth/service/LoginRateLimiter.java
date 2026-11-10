package com.portfolio.ticketing.auth.service;

import com.portfolio.ticketing.common.ratelimit.RateLimiter;
import com.portfolio.ticketing.user.service.UserService;
import org.springframework.stereotype.Component;

@Component
public class LoginRateLimiter {

  private final RateLimiter rateLimiter;

  public LoginRateLimiter(RateLimiter rateLimiter) {
    this.rateLimiter = rateLimiter;
  }

  public void check(String remoteAddress, String email) {
    String subject =
        (remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress)
            + ":"
            + UserService.normalizeEmail(email);
    rateLimiter.check("login", subject);
  }
}
