package com.portfolio.ticketing.reservation.repository;

import java.util.UUID;

public interface ReservationExpirationCandidate {
  UUID getId();

  UUID getTicketTypeId();
}
