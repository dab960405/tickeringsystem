package com.portfolio.ticketing.order.repository;

import java.util.UUID;

public interface OrderExpirationCandidate {
  UUID getOrderId();

  UUID getReservationId();

  UUID getTicketTypeId();

  UUID getEventId();
}
