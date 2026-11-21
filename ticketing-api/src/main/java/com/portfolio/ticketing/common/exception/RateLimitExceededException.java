package com.portfolio.ticketing.common.exception;

public final class RateLimitExceededException extends RuntimeException {

  public RateLimitExceededException(String message) {
    super(message);
  }
}
