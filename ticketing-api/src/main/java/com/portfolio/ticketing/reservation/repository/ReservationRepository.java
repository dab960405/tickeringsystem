package com.portfolio.ticketing.reservation.repository;

import com.portfolio.ticketing.reservation.domain.Reservation;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
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
          WHERE status = 'ACTIVE' AND inventory_disposition = 'HELD'
            AND expires_at <= :now
          ORDER BY expires_at, id
          LIMIT :limit
          """,
      nativeQuery = true)
  List<Reservation> findExpiredCandidates(@Param("now") Instant now, @Param("limit") int limit);

  @Query(
      value =
          """
          SELECT * FROM reservations
          WHERE id IN (:ids)
          ORDER BY id
          FOR UPDATE SKIP LOCKED
          """,
      nativeQuery = true)
  List<Reservation> findAllByIdForUpdate(@Param("ids") Collection<UUID> ids);

  @Query(value = "SELECT clock_timestamp()", nativeQuery = true)
  OffsetDateTime databaseNow();

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE reservations
          SET status = 'CANCELLED', inventory_disposition = 'RELEASED',
              updated_at = CURRENT_TIMESTAMP
          WHERE id = :id AND user_id = :userId
            AND status = 'ACTIVE' AND inventory_disposition = 'HELD'
          """,
      nativeQuery = true)
  int cancelActive(@Param("id") UUID id, @Param("userId") UUID userId);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE reservations
          SET status = 'EXPIRED', inventory_disposition = 'RELEASED',
              updated_at = CURRENT_TIMESTAMP
          WHERE id = :id AND status = 'ACTIVE' AND inventory_disposition = 'HELD'
            AND expires_at <= :now
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
          SET status = 'CONFIRMED', inventory_disposition = 'CONSUMED',
              updated_at = CURRENT_TIMESTAMP
          WHERE id = :id AND user_id = :userId
            AND status = 'ACTIVE' AND inventory_disposition = 'HELD'
            AND expires_at > :evaluationTime
          """,
      nativeQuery = true)
  int confirmActive(
      @Param("id") UUID id,
      @Param("userId") UUID userId,
      @Param("evaluationTime") Instant evaluationTime);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE reservations
          SET status = 'CONFIRMED', inventory_disposition = 'CONSUMED',
              updated_at = CURRENT_TIMESTAMP
          WHERE id = :id AND user_id = :userId
            AND status = 'EXPIRED' AND inventory_disposition = 'RELEASED'
          """,
      nativeQuery = true)
  int confirmRecovered(@Param("id") UUID id, @Param("userId") UUID userId);
}
