package com.portfolio.ticketing.common.exception;

/** Thrown when a ticket type does not have enough available quantity. */
public final class InsufficientStockException extends BusinessException {

  public InsufficientStockException(String message) {
    super(message);
  }
}
