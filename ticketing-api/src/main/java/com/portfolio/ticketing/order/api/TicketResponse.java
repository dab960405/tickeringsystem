package com.portfolio.ticketing.order.api;

import com.portfolio.ticketing.order.domain.Ticket;
import com.portfolio.ticketing.order.domain.TicketStatus;
import java.util.UUID;

public record TicketResponse(
    String code,
    UUID orderId,
    UUID ticketTypeId,
    UUID unitId,
    UUID generationId,
    TicketStatus status) {

  public static TicketResponse from(Ticket ticket) {
    return new TicketResponse(
        ticket.getCode(),
        ticket.getOrderId(),
        ticket.getOrderItem().getTicketTypeId(),
        ticket.getCommercialUnitId(),
        ticket.getGenerationId(),
        ticket.getStatus());
  }
}
