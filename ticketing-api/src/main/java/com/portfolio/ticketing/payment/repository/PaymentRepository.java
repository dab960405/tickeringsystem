package com.portfolio.ticketing.payment.repository;

import com.portfolio.ticketing.payment.domain.Payment;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

  Optional<Payment> findByOrderId(UUID orderId);

  @Query(value = "SELECT * FROM payments WHERE order_id = :orderId FOR UPDATE", nativeQuery = true)
  Optional<Payment> findByOrderIdForUpdate(@Param("orderId") UUID orderId);

  Optional<Payment> findByProviderReference(String providerReference);
}
