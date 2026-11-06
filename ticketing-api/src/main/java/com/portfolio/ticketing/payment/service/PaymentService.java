package com.portfolio.ticketing.payment.service;

import com.portfolio.ticketing.payment.domain.Payment;
import com.portfolio.ticketing.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PaymentService {

  private final PaymentRepository paymentRepository;

  public PaymentService(PaymentRepository paymentRepository) {
    this.paymentRepository = paymentRepository;
  }

  public Payment createPending(UUID orderId, BigDecimal amount) {
    return paymentRepository.save(new Payment(orderId, amount));
  }
}
