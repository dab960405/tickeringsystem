package com.portfolio.ticketing.order.repository;

import com.portfolio.ticketing.order.domain.Order;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, UUID> {

  Optional<Order> findByIdAndUserId(UUID id, UUID userId);

  Optional<Order> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

  boolean existsByReservationId(UUID reservationId);

  Page<Order> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

  /**
   * Finds pending orders whose reservation expired more than 15 minutes ago. This is an unlocked
   * candidate scan; the processor acquires E → T → R → O → P before changing any state.
   */
  @Query(
      value =
          """
          SELECT o.id AS orderId, r.id AS reservationId, r.ticket_type_id AS ticketTypeId,
                 tt.event_id AS eventId
          FROM orders o
          JOIN reservations r ON r.id = o.reservation_id
          JOIN ticket_types tt ON tt.id = r.ticket_type_id
          WHERE o.status = 'PENDING_PAYMENT'
            AND r.expires_at < clock_timestamp() - INTERVAL '15 minutes'
            AND r.status IN ('ACTIVE', 'EXPIRED')
          ORDER BY r.expires_at, o.id
          LIMIT :limit
          """,
      nativeQuery = true)
  List<OrderExpirationCandidate> findExpiredPendingOrderCandidates(@Param("limit") int limit);

  /** Acquires lock O of ADR-016; returns the id so callers revalidate managed state. */
  @Query(value = "SELECT id FROM orders WHERE id = :id FOR UPDATE", nativeQuery = true)
  Optional<UUID> lockById(@Param("id") UUID id);
}
