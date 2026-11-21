package com.portfolio.ticketing.order.repository;

import com.portfolio.ticketing.order.domain.CommercialGeneration;
import com.portfolio.ticketing.order.domain.CommercialGenerationStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommercialGenerationRepository extends JpaRepository<CommercialGeneration, UUID> {

  boolean existsByOrderId(UUID orderId);

  Optional<CommercialGeneration> findByOrderIdAndStatus(
      UUID orderId, CommercialGenerationStatus status);

  List<CommercialGeneration> findByOrderIdOrderByGenerationNumberAsc(UUID orderId);
}
