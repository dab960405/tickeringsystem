package com.portfolio.ticketing.auth.service;

import static org.mockito.Mockito.verify;

import com.portfolio.ticketing.common.ratelimit.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoginRateLimiterTest {

  @Mock private RateLimiter rateLimiter;
  private LoginRateLimiter loginRateLimiter;

  @BeforeEach
  void setUp() {
    loginRateLimiter = new LoginRateLimiter(rateLimiter);
  }

  @Test
  void should_useLoginScopeAndNormalizedSubject() {
    loginRateLimiter.check("127.0.0.1", "User@Example.com");

    verify(rateLimiter).check("login", "127.0.0.1:user@example.com");
  }

  @Test
  void should_useUnknownAddressWhenRemoteAddressIsMissing() {
    loginRateLimiter.check(" ", "user@example.com");

    verify(rateLimiter).check("login", "unknown:user@example.com");
  }
}
