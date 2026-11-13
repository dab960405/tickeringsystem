package com.portfolio.ticketing.order.service;

import java.util.UUID;

/** Outcome of an issuance request; {@code issued=false} means an earlier result was reused. */
public record TicketIssuanceResult(
    UUID generationId, int generationNumber, int ticketCount, boolean issued) {}
