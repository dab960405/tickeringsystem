package com.portfolio.ticketing.tickettype.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

public record TicketTypeRequest(
    @Schema(example = "VIP")
        @NotBlank(message = "name is required")
        @Size(max = 100, message = "name must have at most 100 characters")
        String name,
    @Schema(example = "120.00")
        @NotNull(message = "price is required")
        @DecimalMin(value = "0.00", message = "price cannot be negative")
        @Digits(integer = 10, fraction = 2, message = "price must have at most 2 decimals")
        BigDecimal price,
    @NotNull(message = "totalQuantity is required")
        @Positive(message = "totalQuantity must be positive")
        Integer totalQuantity,
    @NotNull(message = "saleStartsAt is required") Instant saleStartsAt,
    @NotNull(message = "saleEndsAt is required") Instant saleEndsAt,
    @NotNull(message = "maxPerOrder is required")
        @Positive(message = "maxPerOrder must be positive")
        Integer maxPerOrder) {}
