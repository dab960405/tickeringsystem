package com.portfolio.ticketing.common.exception;

/** Thrown when a request is well formed but breaks a business rule. */
public final class BusinessRuleViolationException extends BusinessException {

  public BusinessRuleViolationException(String message) {
    super(message);
  }
}
