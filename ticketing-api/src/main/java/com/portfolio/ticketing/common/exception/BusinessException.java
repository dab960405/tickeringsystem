package com.portfolio.ticketing.common.exception;

/** Root of the business exception hierarchy; mapped to HTTP status codes in a single place. */
public abstract sealed class BusinessException extends RuntimeException
    permits ResourceNotFoundException,
        InsufficientStockException,
        InvalidStateTransitionException,
        DuplicateResourceException,
        BusinessRuleViolationException {

  protected BusinessException(String message) {
    super(message);
  }
}
