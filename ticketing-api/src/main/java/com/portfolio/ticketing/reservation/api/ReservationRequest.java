package com.portfolio.ticketing.reservation.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record ReservationRequest(
    @NotNull(message = "ticketTypeId is required") UUID ticketTypeId,
    @Schema(example = "2")
        @NotNull(message = "quantity is required")
        @Positive(message = "quantity must be positive")
        Integer quantity) {}
