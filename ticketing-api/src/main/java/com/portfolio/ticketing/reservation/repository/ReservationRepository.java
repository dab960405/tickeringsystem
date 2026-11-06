package com.portfolio.ticketing.reservation.repository;

import com.portfolio.ticketing.reservation.domain.Reservation;
import com.portfolio.ticketing.reservation.domain.ReservationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

  Optional<Reservation> findByIdAndUserId(UUID id, UUID userId);

  List<Reservation> findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
      ReservationStatus status, Instant now, Pageable pageable);
}
