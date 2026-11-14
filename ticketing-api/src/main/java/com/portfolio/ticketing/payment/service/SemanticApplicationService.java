package com.portfolio.ticketing.payment.service;

import com.portfolio.ticketing.common.exception.DuplicateResourceException;
import com.portfolio.ticketing.payment.api.PaymentWebhookRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SemanticApplicationService {

  private final JdbcTemplate jdbcTemplate;

  public SemanticApplicationService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Transactional
  public boolean apply(ProviderEventReceipt receipt, Runnable application) {
    PaymentWebhookRequest request = receipt.request();
    String scope = "order:" + request.orderId();
    application.run();

    List<UUID> inserted =
        jdbcTemplate.query(
            """
            INSERT INTO semantic_applications (
                provider_operation_id, application_scope, operation_kind,
                idempotency_key, payload_digest)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            RETURNING id
            """,
            (resultSet, rowNum) -> resultSet.getObject(1, UUID.class),
            receipt.operationId(),
            scope,
            request.operationKind().name(),
            request.providerOperationId(),
            receipt.payloadDigest());
    if (!inserted.isEmpty()) {
      return true;
    }

    String existingDigest = findDigest(scope, request);
    if (!existingDigest.equals(receipt.payloadDigest())) {
      throw new DuplicateResourceException("Semantic operation identity has different evidence");
    }
    return false;
  }

  private String findDigest(String scope, PaymentWebhookRequest request) {
    return jdbcTemplate
        .query(
            """
            SELECT payload_digest FROM semantic_applications
            WHERE application_scope = ? AND operation_kind = ? AND idempotency_key = ?
            """,
            (resultSet, rowNum) -> resultSet.getString(1),
            scope,
            request.operationKind().name(),
            request.providerOperationId())
        .stream()
        .findFirst()
        .orElseThrow(
            () -> new DuplicateResourceException("Provider operation was already applied"));
  }
}
