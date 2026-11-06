package com.portfolio.ticketing.payment.domain;

import com.portfolio.ticketing.common.exception.InvalidStateTransitionException;

public enum PaymentStatus {
  PENDING,
  SUCCEEDED,
  FAILED,
  REFUNDED;

  public boolean canTransitionTo(PaymentStatus target) {
    return switch (this) {
      case PENDING -> target == SUCCEEDED || target == FAILED;
      case SUCCEEDED -> target == REFUNDED;
      case FAILED, REFUNDED -> false;
    };
  }

  public PaymentStatus transitionTo(PaymentStatus target) {
    if (!canTransitionTo(target)) {
      throw new InvalidStateTransitionException(
          "Payment cannot move from " + this + " to " + target);
    }
    return target;
  }
}
