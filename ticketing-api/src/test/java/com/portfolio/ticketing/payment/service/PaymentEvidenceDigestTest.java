package com.portfolio.ticketing.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.ticketing.payment.api.PaymentWebhookRequest;
import com.portfolio.ticketing.payment.domain.PaymentStatus;
import com.portfolio.ticketing.payment.domain.ProviderOperationKind;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentEvidenceDigestTest {

  @Test
  void should_produceSameSemanticDigest_when_amountScaleAndRawPayloadDiffer() {
    PaymentWebhookRequest first = request(new BigDecimal("40.00"));
    PaymentWebhookRequest second = request(new BigDecimal("40.0"));

    assertThat(PaymentEvidenceDigest.semantic(first))
        .isEqualTo(PaymentEvidenceDigest.semantic(second));
  }

  @Test
  void should_produceDifferentSemanticDigest_when_financialEvidenceChanges() {
    PaymentWebhookRequest first = request(new BigDecimal("40.00"));
    PaymentWebhookRequest changed = request(new BigDecimal("41.00"));

    assertThat(PaymentEvidenceDigest.semantic(first))
        .isNotEqualTo(PaymentEvidenceDigest.semantic(changed));
  }

  @Test
  void should_produceDifferentRawDigest_when_eventPayloadChanges() {
    assertThat(PaymentEvidenceDigest.raw("{\"amount\":40.00}"))
        .isNotEqualTo(PaymentEvidenceDigest.raw("{\"amount\":40.0}"));
  }

  private static PaymentWebhookRequest request(BigDecimal amount) {
    return new PaymentWebhookRequest(
        UUID.fromString("018f2f70-1234-7000-8000-000000000001"),
        PaymentStatus.SUCCEEDED,
        "provider-reference",
        UUID.fromString("018f2f70-1234-7000-8000-000000000002"),
        "provider-operation",
        ProviderOperationKind.PAYMENT_CAPTURE,
        amount,
        "USD");
  }
}
