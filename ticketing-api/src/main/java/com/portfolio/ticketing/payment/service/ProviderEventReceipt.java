package com.portfolio.ticketing.payment.service;

import com.portfolio.ticketing.payment.api.PaymentWebhookRequest;
import java.util.UUID;

public record ProviderEventReceipt(
    UUID eventId, UUID operationId, String payloadDigest, PaymentWebhookRequest request) {}
