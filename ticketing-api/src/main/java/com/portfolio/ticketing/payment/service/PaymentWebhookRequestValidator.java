package com.portfolio.ticketing.payment.service;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.payment.api.PaymentWebhookRequest;
import com.portfolio.ticketing.payment.domain.PaymentStatus;
import com.portfolio.ticketing.payment.domain.ProviderOperationKind;

public final class PaymentWebhookRequestValidator {

  private PaymentWebhookRequestValidator() {}

  public static void validate(PaymentWebhookRequest request) {
    if (request == null
        || request.orderId() == null
        || request.status() == null
        || request.providerEventId() == null
        || request.providerOperationId() == null
        || request.providerOperationId().isBlank()
        || request.providerOperationId().length() > 100
        || request.operationKind() == null
        || request.amount() == null
        || request.amount().signum() < 0
        || request.currency() == null
        || !request.currency().matches("[A-Z]{3}")) {
      throw new BusinessRuleViolationException("Complete provider evidence is required");
    }
    if ((request.operationKind() == ProviderOperationKind.PAYMENT_CAPTURE
            && request.status() != PaymentStatus.SUCCEEDED)
        || (request.operationKind() == ProviderOperationKind.PAYMENT_FAILURE
            && request.status() != PaymentStatus.FAILED)) {
      throw new BusinessRuleViolationException("Operation kind and status are inconsistent");
    }
    if ((request.operationKind() == ProviderOperationKind.PAYMENT_CAPTURE
            || request.status() == PaymentStatus.REFUNDED)
        && (request.providerReference() == null || request.providerReference().isBlank())) {
      throw new BusinessRuleViolationException("providerReference is required for this operation");
    }
  }
}
