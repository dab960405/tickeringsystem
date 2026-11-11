package com.portfolio.ticketing.order.repository;

import com.portfolio.ticketing.order.domain.Order;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, UUID> {

  Optional<Order> findByIdAndUserId(UUID id, UUID userId);

  Optional<Order> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

  boolean existsByReservationId(UUID reservationId);

  Page<Order> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
