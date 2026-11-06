package com.portfolio.ticketing.reservation.api;

import com.portfolio.ticketing.reservation.domain.Reservation;
import com.portfolio.ticketing.reservation.domain.ReservationStatus;
import java.time.Instant;
import java.util.UUID;

public record ReservationResponse(
    UUID id, UUID ticketTypeId, int quantity, ReservationStatus status, Instant expiresAt) {

  public static ReservationResponse from(Reservation reservation) {
    return new ReservationResponse(
        reservation.getId(),
        reservation.getTicketType().getId(),
        reservation.getQuantity(),
        reservation.getStatus(),
        reservation.getExpiresAt());
  }
}
