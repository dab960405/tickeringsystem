package com.portfolio.ticketing.payment.repository;

import com.portfolio.ticketing.payment.domain.Payment;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

  Optional<Payment> findByOrderId(UUID orderId);

  @org.springframework.data.jpa.repository.Query(
      value = "SELECT * FROM payments WHERE order_id = :orderId FOR UPDATE", nativeQuery = true)
  Optional<Payment> findByOrderIdForUpdate(@org.springframework.data.repository.query.Param("orderId") UUID orderId);

  Optional<Payment> findByProviderReference(String providerReference);
}
