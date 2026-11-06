package com.portfolio.ticketing.auth.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @Schema(example = "user@ticketing.dev")
        @NotBlank(message = "email is required")
        @Email(message = "email must be valid")
        String email,
    @Schema(example = "Password123", format = "password")
        @NotBlank(message = "password is required")
        String password) {}
