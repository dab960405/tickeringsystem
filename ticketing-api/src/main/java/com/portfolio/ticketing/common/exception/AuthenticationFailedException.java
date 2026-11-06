package com.portfolio.ticketing.common.exception;

public final class AuthenticationFailedException extends RuntimeException {

  public AuthenticationFailedException(String message) {
    super(message);
  }

  public AuthenticationFailedException(String message, Throwable cause) {
    super(message, cause);
  }
}
