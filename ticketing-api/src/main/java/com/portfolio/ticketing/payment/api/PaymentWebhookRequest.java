package com.portfolio.ticketing.payment.api;

import com.portfolio.ticketing.payment.domain.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record PaymentWebhookRequest(
    @NotNull(message = "orderId is required") UUID orderId,
    @NotNull(message = "status is required") PaymentStatus status,
    @Schema(example = "provider-123") String providerReference) {}
