package com.portfolio.ticketing.reservation.repository;

import com.portfolio.ticketing.reservation.domain.Reservation;
import com.portfolio.ticketing.reservation.domain.ReservationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

  Optional<Reservation> findByIdAndUserId(UUID id, UUID userId);

  List<Reservation> findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
      ReservationStatus status, Instant now, Pageable pageable);

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
}
