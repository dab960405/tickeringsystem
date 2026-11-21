package com.portfolio.ticketing.common.exception;

/** Thrown when a resource does not exist or is not visible to the caller. */
public final class ResourceNotFoundException extends BusinessException {

  public ResourceNotFoundException(String message) {
    super(message);
  }
}
