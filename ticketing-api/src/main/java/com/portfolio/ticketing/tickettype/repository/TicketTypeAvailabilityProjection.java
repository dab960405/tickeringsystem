package com.portfolio.ticketing.tickettype.repository;

import java.util.UUID;

/**
 * Live inventory fields are queried from PostgreSQL and are never served from the catalog cache.
 */
public interface TicketTypeAvailabilityProjection {

  UUID getId();

  int getAvailableQuantity();
}
