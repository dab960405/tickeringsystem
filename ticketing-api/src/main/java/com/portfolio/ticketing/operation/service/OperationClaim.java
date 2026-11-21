package com.portfolio.ticketing.operation.service;

import java.util.UUID;

record OperationClaim(
    UUID jobId,
    UUID executionId,
    UUID participationId,
    UUID eventId,
    UUID operationId,
    UUID orderId,
    String payload,
    String payloadDigest,
    int attemptCount,
    long fencingToken) {}
