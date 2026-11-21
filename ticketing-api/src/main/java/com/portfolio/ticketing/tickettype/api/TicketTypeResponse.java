package com.portfolio.ticketing.tickettype.api;

import com.portfolio.ticketing.tickettype.domain.TicketType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TicketTypeResponse(
    UUID id,
    String name,
    BigDecimal price,
    String currency,
    int totalQuantity,
    int availableQuantity,
    Instant saleStartsAt,
    Instant saleEndsAt,
    int maxPerOrder) {

  public static TicketTypeResponse from(TicketType ticketType) {
    return new TicketTypeResponse(
        ticketType.getId(),
        ticketType.getName(),
        ticketType.getPrice(),
        ticketType.getCurrency(),
        ticketType.getTotalQuantity(),
        ticketType.getAvailableQuantity(),
        ticketType.getSaleStartsAt(),
        ticketType.getSaleEndsAt(),
        ticketType.getMaxPerOrder());
  }
}
