package com.portfolio.ticketing.order.repository;

import com.portfolio.ticketing.order.domain.CommercialUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommercialUnitRepository extends JpaRepository<CommercialUnit, UUID> {

  @Query(
      """
      SELECT u FROM CommercialUnit u JOIN FETCH u.orderItem i
      WHERE u.orderId = :orderId
      ORDER BY i.id, u.unitNumber
      """)
  List<CommercialUnit> findForOrder(@Param("orderId") UUID orderId);
}
