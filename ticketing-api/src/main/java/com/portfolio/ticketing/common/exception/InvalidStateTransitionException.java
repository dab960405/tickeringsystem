package com.portfolio.ticketing.common.exception;

/** Thrown when a state machine receives a transition that is not allowed. */
public final class InvalidStateTransitionException extends BusinessException {

  public InvalidStateTransitionException(String message) {
    super(message);
  }
}
