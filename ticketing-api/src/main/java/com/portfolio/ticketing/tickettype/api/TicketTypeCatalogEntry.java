package com.portfolio.ticketing.tickettype.api;

import com.portfolio.ticketing.tickettype.domain.TicketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Cacheable catalog metadata. Inventory availability is deliberately excluded because reservations
 * change it independently and it must always be read from PostgreSQL.
 */
public record TicketTypeCatalogEntry(
    UUID id,
    String name,
    BigDecimal price,
    String currency,
    int totalQuantity,
    Instant saleStartsAt,
    Instant saleEndsAt,
    int maxPerOrder) {

  public static TicketTypeCatalogEntry from(TicketType ticketType) {
    return new TicketTypeCatalogEntry(
        ticketType.getId(),
        ticketType.getName(),
        ticketType.getPrice(),
        ticketType.getCurrency(),
        ticketType.getTotalQuantity(),
        ticketType.getSaleStartsAt(),
        ticketType.getSaleEndsAt(),
        ticketType.getMaxPerOrder());
  }

  public TicketTypeResponse toResponse(int availableQuantity) {
    return new TicketTypeResponse(
        id,
        name,
        price,
        currency,
        totalQuantity,
        availableQuantity,
        saleStartsAt,
        saleEndsAt,
        maxPerOrder);
  }
}
