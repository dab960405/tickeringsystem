package com.portfolio.ticketing.common.ratelimit;

public interface RateLimiter {

  void check(String scope, String subject);
}
