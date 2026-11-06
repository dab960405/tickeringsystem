package com.portfolio.ticketing.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.ticketing.common.exception.BusinessRuleViolationException;
import com.portfolio.ticketing.common.exception.InvalidStateTransitionException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentTest {

  @Test
  void should_createPendingPaymentWithOrderAmount() {
    Payment payment = new Payment(UUID.randomUUID(), new BigDecimal("80.00"));

    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    assertThat(payment.getAmount()).isEqualByComparingTo("80.00");
    assertThat(payment.getCurrency()).isEqualTo("USD");
    assertThat(payment.getProviderReference()).isNull();
  }

  @Test
  void should_storeProviderReference_when_paymentSucceeds() {
    Payment payment = newPayment();

    payment.markSucceeded("provider-123");

    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    assertThat(payment.getProviderReference()).isEqualTo("provider-123");
  }

  @Test
  void should_markPaymentAsFailed() {
    Payment payment = newPayment();

    payment.markFailed();

    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
  }

  @Test
  void should_refundSucceededPayment() {
    Payment payment = newPayment();
    payment.markSucceeded("provider-123");

    payment.refund();

    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
  }

  @Test
  void should_rejectSuccessAfterFailure() {
    Payment payment = newPayment();
    payment.markFailed();

    assertThatThrownBy(() -> payment.markSucceeded("provider-123"))
        .isInstanceOf(InvalidStateTransitionException.class);
  }

  @Test
  void should_rejectNegativeAmount() {
    assertThatThrownBy(() -> new Payment(UUID.randomUUID(), new BigDecimal("-1.00")))
        .isInstanceOf(BusinessRuleViolationException.class);
  }

  @Test
  void should_rejectBlankProviderReference() {
    Payment payment = newPayment();

    assertThatThrownBy(() -> payment.markSucceeded(" "))
        .isInstanceOf(BusinessRuleViolationException.class);
  }

  private static Payment newPayment() {
    return new Payment(UUID.randomUUID(), new BigDecimal("80.00"));
  }
}
