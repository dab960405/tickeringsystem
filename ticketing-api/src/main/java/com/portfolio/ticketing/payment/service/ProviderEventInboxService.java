package com.portfolio.ticketing.payment.service;

import com.portfolio.ticketing.common.exception.DuplicateResourceException;
import com.portfolio.ticketing.common.exception.ResourceNotFoundException;
import com.portfolio.ticketing.operation.config.OperationProperties;
import com.portfolio.ticketing.operation.service.OperationCycleService;
import com.portfolio.ticketing.operation.service.OperationTransactionExecutor;
import com.portfolio.ticketing.payment.api.PaymentWebhookRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ProviderEventInboxService {

  private final JdbcTemplate jdbcTemplate;
  private final OperationProperties properties;
  private final OperationTransactionExecutor transactionExecutor;
  private final OperationCycleService cycleService;

  public ProviderEventInboxService(
      JdbcTemplate jdbcTemplate,
      OperationProperties properties,
      OperationTransactionExecutor transactionExecutor,
      OperationCycleService cycleService) {
    this.jdbcTemplate = jdbcTemplate;
    this.properties = properties;
    this.transactionExecutor = transactionExecutor;
    this.cycleService = cycleService;
  }

  public ProviderEventReceipt receive(PaymentWebhookRequest request, String payload) {
    return transactionExecutor.rx(() -> {
      PaymentWebhookRequestValidator.validate(request);
      requireOrder(request);
      String semanticDigest = PaymentEvidenceDigest.semantic(request);
      UUID operationId = registerOperation(request, semanticDigest);
      UUID eventId = registerEvent(request, payload, operationId);
      cycleService.register(eventId, request.orderId());
      return new ProviderEventReceipt(eventId, operationId, semanticDigest, request);
    });
  }

  private void requireOrder(PaymentWebhookRequest request) {
    Boolean exists =
        jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM orders WHERE id = ?)", Boolean.class, request.orderId());
    if (!Boolean.TRUE.equals(exists)) {
      throw new ResourceNotFoundException("Order " + request.orderId() + " not found");
    }
  }

  private UUID registerOperation(PaymentWebhookRequest request, String semanticDigest) {
    OperationProperties.Provider provider = properties.getProvider();
    List<UUID> inserted =
        jdbcTemplate.query(
            """
            INSERT INTO provider_operations (
                provider_code, merchant_account_id, identity_namespace, provider_operation_id,
                order_id, operation_kind, payload_digest)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (provider_code, merchant_account_id, identity_namespace, provider_operation_id)
            DO NOTHING
            RETURNING id
            """,
            (resultSet, rowNum) -> resultSet.getObject(1, UUID.class),
            provider.getCode(),
            provider.getMerchantAccountId(),
            provider.getIdentityNamespace(),
            request.providerOperationId(),
            request.orderId(),
            request.operationKind().name(),
            semanticDigest);
    if (!inserted.isEmpty()) {
      return inserted.getFirst();
    }

    StoredOperation existing = findOperation(request);
    if (!existing.orderId().equals(request.orderId())
        || !existing.operationKind().equals(request.operationKind().name())
        || !existing.payloadDigest().equals(semanticDigest)) {
      throw new DuplicateResourceException("Provider operation identity has different evidence");
    }
    return existing.id();
  }

  private StoredOperation findOperation(PaymentWebhookRequest request) {
    OperationProperties.Provider provider = properties.getProvider();
    return jdbcTemplate
        .query(
            """
            SELECT id, order_id, operation_kind, payload_digest
            FROM provider_operations
            WHERE provider_code = ? AND merchant_account_id = ?
              AND identity_namespace = ? AND provider_operation_id = ?
            """,
            (resultSet, rowNum) ->
                new StoredOperation(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("order_id", UUID.class),
                    resultSet.getString("operation_kind"),
                    resultSet.getString("payload_digest")),
            provider.getCode(),
            provider.getMerchantAccountId(),
            provider.getIdentityNamespace(),
            request.providerOperationId())
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Provider operation could not be read"));
  }

  private UUID registerEvent(PaymentWebhookRequest request, String payload, UUID operationId) {
    OperationProperties.Provider provider = properties.getProvider();
    String rawDigest = PaymentEvidenceDigest.raw(payload);
    List<UUID> inserted =
        jdbcTemplate.query(
            """
            INSERT INTO provider_events (
                provider_code, merchant_account_id, provider_event_id,
                provider_operation_id, payload, payload_digest)
            VALUES (?, ?, ?, ?, CAST (? AS jsonb), ?)
            ON CONFLICT (provider_code, merchant_account_id, provider_event_id)
            DO NOTHING
            RETURNING id
            """,
            (resultSet, rowNum) -> resultSet.getObject(1, UUID.class),
            provider.getCode(),
            provider.getMerchantAccountId(),
            request.providerEventId(),
            operationId,
            payload,
            rawDigest);
    if (!inserted.isEmpty()) {
      return inserted.getFirst();
    }

    StoredEvent existing = findEvent(request);
    if (!existing.operationId().equals(operationId)
        || !existing.payloadDigest().equals(rawDigest)) {
      throw new DuplicateResourceException("Provider event identity has a different payload");
    }
    return existing.id();
  }

  private StoredEvent findEvent(PaymentWebhookRequest request) {
    OperationProperties.Provider provider = properties.getProvider();
    return jdbcTemplate
        .query(
            """
            SELECT id, provider_operation_id, payload_digest
            FROM provider_events
            WHERE provider_code = ? AND merchant_account_id = ? AND provider_event_id = ?
            """,
            (resultSet, rowNum) ->
                new StoredEvent(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("provider_operation_id", UUID.class),
                    resultSet.getString("payload_digest")),
            provider.getCode(),
            provider.getMerchantAccountId(),
            request.providerEventId())
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Provider event could not be read"));
  }

  private record StoredOperation(
      UUID id, UUID orderId, String operationKind, String payloadDigest) {}

  private record StoredEvent(UUID id, UUID operationId, String payloadDigest) {}
}
