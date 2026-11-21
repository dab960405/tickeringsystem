package com.portfolio.ticketing.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.payment.domain.Payment;
import com.portfolio.ticketing.payment.domain.PaymentStatus;
import com.portfolio.ticketing.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

  @Mock private PaymentRepository paymentRepository;

  @Test
  void should_createPendingPaymentForOrder() {
    PaymentService paymentService = new PaymentService(paymentRepository);
    UUID orderId = UUID.randomUUID();
    when(paymentRepository.save(any(Payment.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    Payment payment = paymentService.createPending(orderId, new BigDecimal("80.00"));

    assertThat(payment.getOrderId()).isEqualTo(orderId);
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    assertThat(payment.getAmount()).isEqualByComparingTo("80.00");
    verify(paymentRepository).save(payment);
  }

  @Test
  void should_findPaymentByOrderId() {
    PaymentService paymentService = new PaymentService(paymentRepository);
    UUID orderId = UUID.randomUUID();
    Payment payment = new Payment(orderId, BigDecimal.TEN);
    when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(payment));

    assertThat(paymentService.findRequiredByOrderId(orderId)).isSameAs(payment);
  }

  @Test
  void should_throwNotFound_when_paymentDoesNotExist() {
    PaymentService paymentService = new PaymentService(paymentRepository);
    UUID orderId = UUID.randomUUID();
    when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> paymentService.findRequiredByOrderId(orderId))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void should_savePaymentChanges() {
    PaymentService paymentService = new PaymentService(paymentRepository);
    Payment payment = new Payment(UUID.randomUUID(), BigDecimal.TEN);
    when(paymentRepository.save(payment)).thenReturn(payment);

    assertThat(paymentService.save(payment)).isSameAs(payment);
  }
}
