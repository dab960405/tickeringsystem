package com.portfolio.ticketing.order.domain;

import com.portfolio.ticketing.common.exception.InvalidStateTransitionException;

public enum OrderStatus {
  PENDING_PAYMENT,
  PAID,
  CANCELLED,
  EXPIRED,
  REFUNDED;

  public boolean canTransitionTo(OrderStatus target) {
    return switch (this) {
      case PENDING_PAYMENT -> target == PAID || target == CANCELLED || target == EXPIRED;
      case PAID -> target == REFUNDED;
      case CANCELLED, EXPIRED, REFUNDED -> false;
    };
  }

  public OrderStatus transitionTo(OrderStatus target) {
    if (!canTransitionTo(target)) {
      throw new InvalidStateTransitionException("Order cannot move from " + this + " to " + target);
    }
    return target;
  }

  public boolean isFinal() {
    return this == CANCELLED || this == EXPIRED || this == REFUNDED;
  }
}
