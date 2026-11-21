package com.portfolio.ticketing.auth.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record RefreshTokenRequest(
    @Schema(description = "Refresh token returned by login")
        @NotBlank(message = "refreshToken is required")
        String refreshToken) {}
