package com.portfolio.ticketing.reservation.repository;

import com.portfolio.ticketing.reservation.domain.Reservation;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

  Optional<Reservation> findByIdAndUserId(UUID id, UUID userId);

  @Query(
      value =
          """
          SELECT * FROM reservations
          WHERE status = 'ACTIVE' AND expires_at < :now
          ORDER BY expires_at
          LIMIT :limit
          FOR UPDATE SKIP LOCKED
          """,
      nativeQuery = true)
  List<Reservation> findExpiredForUpdate(@Param("now") Instant now, @Param("limit") int limit);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE reservations
          SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP
          WHERE id = :id AND user_id = :userId AND status = 'ACTIVE'
          """,
      nativeQuery = true)
  int cancelActive(@Param("id") UUID id, @Param("userId") UUID userId);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE reservations
          SET status = 'EXPIRED', updated_at = CURRENT_TIMESTAMP
          WHERE id = :id AND status = 'ACTIVE' AND expires_at < :now
          """,
      nativeQuery = true)
  int expireActive(@Param("id") UUID id, @Param("now") Instant now);

  @Query(
      value = "SELECT * FROM reservations WHERE id = :id AND user_id = :userId FOR UPDATE",
      nativeQuery = true)
  Optional<Reservation> findOwnedForUpdate(@Param("id") UUID id, @Param("userId") UUID userId);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE reservations
          SET status = 'CONFIRMED', updated_at = CURRENT_TIMESTAMP
          WHERE id = :id AND user_id = :userId AND status IN ('ACTIVE', 'EXPIRED')
          """,
      nativeQuery = true)
  int confirmForPayment(@Param("id") UUID id, @Param("userId") UUID userId);
}
