package com.portfolio.ticketing.venue.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record VenueRequest(
    @Schema(example = "Estadio Nacional")
        @NotBlank(message = "name is required")
        @Size(max = 150, message = "name must have at most 150 characters")
        String name,
    @Schema(example = "Lima")
        @NotBlank(message = "city is required")
        @Size(max = 100, message = "city must have at most 100 characters")
        String city,
    @Schema(example = "Av. José Díaz s/n")
        @NotBlank(message = "address is required")
        @Size(max = 255, message = "address must have at most 255 characters")
        String address,
    @Schema(example = "45000")
        @NotNull(message = "capacity is required")
        @Positive(message = "capacity must be positive")
        Integer capacity) {}
