package com.portfolio.ticketing.auth.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @Schema(example = "new.user@example.com")
        @NotBlank(message = "email is required")
        @Email(message = "email must be valid")
        @Size(max = 255, message = "email must have at most 255 characters")
        String email,
    @Schema(example = "Password123", format = "password")
        @NotBlank(message = "password is required")
        @Size(min = 8, max = 72, message = "password must have between 8 and 72 characters")
        @Pattern(
            regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
            message = "password must contain at least one letter and one number")
        String password,
    @Schema(example = "New User")
        @NotBlank(message = "fullName is required")
        @Size(max = 150, message = "fullName must have at most 150 characters")
        String fullName) {}
