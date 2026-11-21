package com.portfolio.ticketing.order.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record OrderRequest(@NotNull(message = "reservationId is required") UUID reservationId) {}
