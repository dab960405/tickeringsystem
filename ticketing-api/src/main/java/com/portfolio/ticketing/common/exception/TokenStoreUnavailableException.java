package com.portfolio.ticketing.common.exception;

public final class TokenStoreUnavailableException extends RuntimeException {

  public TokenStoreUnavailableException(Throwable cause) {
    super("Refresh token store is temporarily unavailable", cause);
  }
}
