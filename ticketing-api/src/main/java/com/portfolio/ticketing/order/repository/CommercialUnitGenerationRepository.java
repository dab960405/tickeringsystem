package com.portfolio.ticketing.order.repository;

import com.portfolio.ticketing.order.domain.CommercialUnitGeneration;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommercialUnitGenerationRepository
    extends JpaRepository<CommercialUnitGeneration, UUID> {

  long countByGenerationId(UUID generationId);
}
