package com.portfolio.ticketing.payment.api;

import com.portfolio.ticketing.payment.domain.PaymentStatus;
import com.portfolio.ticketing.payment.domain.ProviderOperationKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

public record PaymentWebhookRequest(
    @NotNull(message = "orderId is required") UUID orderId,
    @NotNull(message = "status is required") PaymentStatus status,
    @Schema(example = "provider-123") String providerReference,
    @NotNull(message = "providerEventId is required") UUID providerEventId,
    @NotBlank(message = "providerOperationId is required")
        @Size(max = 100, message = "providerOperationId must contain at most 100 characters")
        String providerOperationId,
    @NotNull(message = "operationKind is required") ProviderOperationKind operationKind,
    @NotNull(message = "amount is required")
        @DecimalMin(value = "0.00", message = "amount cannot be negative")
        BigDecimal amount,
    @NotBlank(message = "currency is required")
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be an ISO-4217 uppercase code")
        String currency) {}
