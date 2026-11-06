package com.portfolio.ticketing.payment.repository;

import com.portfolio.ticketing.payment.domain.Payment;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

  Optional<Payment> findByOrderId(UUID orderId);

  Optional<Payment> findByProviderReference(String providerReference);
}
