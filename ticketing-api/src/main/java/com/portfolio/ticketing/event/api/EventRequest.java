package com.portfolio.ticketing.event.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public record EventRequest(
    @NotNull(message = "venueId is required") UUID venueId,
    @Schema(example = "Rock en el Parque")
        @NotBlank(message = "title is required")
        @Size(max = 200, message = "title must have at most 200 characters")
        String title,
    @Size(max = 5000, message = "description must have at most 5000 characters") String description,
    @Schema(example = "MUSIC")
        @NotBlank(message = "category is required")
        @Size(max = 50, message = "category must have at most 50 characters")
        String category,
    @NotNull(message = "startsAt is required") @Future(message = "startsAt must be in the future")
        Instant startsAt,
    @NotNull(message = "endsAt is required") Instant endsAt) {}
