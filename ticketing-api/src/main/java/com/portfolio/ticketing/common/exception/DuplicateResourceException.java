package com.portfolio.ticketing.common.exception;

/** Thrown when a unique business key already exists. */
public final class DuplicateResourceException extends BusinessException {

  public DuplicateResourceException(String message) {
    super(message);
  }
}
