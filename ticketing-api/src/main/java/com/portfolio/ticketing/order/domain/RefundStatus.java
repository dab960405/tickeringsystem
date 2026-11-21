package com.portfolio.ticketing.order.domain;

public enum RefundStatus {
  NONE,
  REQUIRED,
  REQUESTED,
  PENDING_RECONCILIATION,
  FAILED,
  CONFIRMED
}
