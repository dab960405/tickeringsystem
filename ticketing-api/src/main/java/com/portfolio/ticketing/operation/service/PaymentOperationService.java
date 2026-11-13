package com.portfolio.ticketing.operation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.ticketing.order.service.OrderPaymentService;
import com.portfolio.ticketing.payment.api.PaymentWebhookRequest;
import com.portfolio.ticketing.payment.service.ProviderEventReceipt;
import com.portfolio.ticketing.payment.service.SemanticApplicationService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PaymentOperationService {

  private static final Logger log = LoggerFactory.getLogger(PaymentOperationService.class);

  private final OperationJobService jobService;
  private final OperationTransactionExecutor transactionExecutor;
  private final SemanticApplicationService semanticApplicationService;
  private final OrderPaymentService orderPaymentService;
  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public PaymentOperationService(
      OperationJobService jobService,
      OperationTransactionExecutor transactionExecutor,
      SemanticApplicationService semanticApplicationService,
      OrderPaymentService orderPaymentService,
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper) {
    this.jobService = jobService;
    this.transactionExecutor = transactionExecutor;
    this.semanticApplicationService = semanticApplicationService;
    this.orderPaymentService = orderPaymentService;
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  public void process(ProviderEventReceipt receipt) {
    try {
      jobService
          .findEffectiveJobIdForEvent(receipt.eventId())
          .ifPresent(jobId -> processJob(jobId, "webhook-" + UUID.randomUUID()));
    } catch (OperationFencingException exception) {
      log.debug("Webhook execution superseded for event {}", receipt.eventId());
    }
  }

  void processJob(UUID jobId, String workerId) {
    jobService
        .claim(jobId, workerId)
        .ifPresent(claim -> {
          try {
            apply(claim);
            project(claim);
          } catch (OperationFencingException exception) {
            throw exception;
          } catch (RuntimeException exception) {
            jobService.recordFailure(claim, exception);
            throw exception;
          }
        });
  }

  void apply(OperationClaim claim) {
    PaymentWebhookRequest request = readRequest(claim.payload());
    transactionExecutor.apply(() -> {
      requireCurrentExecution(claim, "CLAIMED");
      ProviderEventReceipt receipt =
          new ProviderEventReceipt(
              claim.eventId(), claim.operationId(), claim.payloadDigest(), request);
      boolean applied =
          semanticApplicationService.apply(receipt, () -> orderPaymentService.process(request));
      jdbcTemplate.update(
          """
          INSERT INTO operation_dispositions (execution_id, disposition_type, payload)
          VALUES (?, ?, CAST (? AS jsonb))
          """,
          claim.executionId(),
          applied ? "APPLIED" : "NO_OP",
          dispositionPayload(claim, applied));
      jdbcTemplate.update(
          """
          UPDATE operation_executions SET status = 'APPLIED', updated_at = clock_timestamp()
          WHERE id = ?
          """,
          claim.executionId());
      jdbcTemplate.update(
          """
          UPDATE operation_jobs SET status = 'APPLIED', updated_at = clock_timestamp()
          WHERE id = ?
          """,
          claim.jobId());
      return null;
    });
  }

  void project(OperationClaim claim) {
    transactionExecutor.project(() -> {
      requireCurrentExecution(claim, "APPLIED");
      CommercialEvidence evidence = loadCommercialEvidence(claim.orderId());
      int projected =
          jdbcTemplate.update(
              """
              INSERT INTO operation_projections (
                  participation_id, technical_status, commercial_status,
                  financial_status, fencing_token)
              VALUES (?, 'COMPLETED', ?, ?, ?)
              ON CONFLICT (participation_id) DO UPDATE SET
                  technical_status = EXCLUDED.technical_status,
                  commercial_status = EXCLUDED.commercial_status,
                  financial_status = EXCLUDED.financial_status,
                  fencing_token = EXCLUDED.fencing_token,
                  version = operation_projections.version + 1,
                  updated_at = clock_timestamp()
              WHERE operation_projections.fencing_token <= EXCLUDED.fencing_token
              """,
              claim.participationId(),
              evidence.commercialStatus(),
              evidence.financialStatus(),
              claim.fencingToken());
      if (projected != 1) {
        throw new OperationFencingException("Projection token is stale");
      }
      jdbcTemplate.update(
          """
          UPDATE operation_executions SET status = 'COMPLETED', updated_at = clock_timestamp()
          WHERE id = ?
          """,
          claim.executionId());
      jdbcTemplate.update(
          """
          UPDATE operation_jobs SET status = 'COMPLETED', updated_at = clock_timestamp()
          WHERE id = ?
          """,
          claim.jobId());
      return null;
    });
  }

  private void requireCurrentExecution(OperationClaim claim, String expectedStatus) {
    LockedJob job =
        jdbcTemplate
            .query(
                """
                SELECT effective, fencing_token, status FROM operation_jobs WHERE id = ? FOR UPDATE
                """,
                (resultSet, rowNum) ->
                    new LockedJob(
                        resultSet.getBoolean("effective"),
                        resultSet.getLong("fencing_token"),
                        resultSet.getString("status")),
                claim.jobId())
            .stream()
            .findFirst()
            .orElseThrow(() -> new OperationFencingException("Operation job no longer exists"));
    LockedExecution execution =
        jdbcTemplate
            .query(
                """
                SELECT fencing_token, lease_expires_at, status
                FROM operation_executions WHERE id = ? FOR UPDATE
                """,
                (resultSet, rowNum) ->
                    new LockedExecution(
                        resultSet.getLong("fencing_token"),
                        resultSet.getObject("lease_expires_at", OffsetDateTime.class).toInstant(),
                        resultSet.getString("status")),
                claim.executionId())
            .stream()
            .findFirst()
            .orElseThrow(
                () -> new OperationFencingException("Operation execution no longer exists"));
    Instant evaluationTime =
        jdbcTemplate.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant();
    if (!job.effective()
        || job.fencingToken() != claim.fencingToken()
        || !job.status().equals(expectedStatus)
        || execution.fencingToken() != claim.fencingToken()
        || !execution.status().equals(expectedStatus)
        || !execution.leaseExpiresAt().isAfter(evaluationTime)) {
      throw new OperationFencingException("Operation execution is stale or its lease expired");
    }
  }

  private CommercialEvidence loadCommercialEvidence(UUID orderId) {
    return jdbcTemplate
        .query(
            """
            SELECT o.status AS commercial_status, p.status AS financial_status
            FROM orders o JOIN payments p ON p.order_id = o.id WHERE o.id = ?
            """,
            (resultSet, rowNum) ->
                new CommercialEvidence(
                    resultSet.getString("commercial_status"),
                    resultSet.getString("financial_status")),
            orderId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Commercial evidence not found"));
  }

  private PaymentWebhookRequest readRequest(String payload) {
    try {
      return objectMapper.readValue(payload, PaymentWebhookRequest.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Stored provider payload is invalid", exception);
    }
  }

  private String dispositionPayload(OperationClaim claim, boolean applied) {
    try {
      return objectMapper.writeValueAsString(
          Map.of(
              "eventId", claim.eventId(),
              "operationId", claim.operationId(),
              "result", applied ? "APPLIED" : "ALREADY_APPLIED"));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Could not serialize operation disposition", exception);
    }
  }

  private record LockedJob(boolean effective, long fencingToken, String status) {}

  private record LockedExecution(long fencingToken, Instant leaseExpiresAt, String status) {}

  private record CommercialEvidence(String commercialStatus, String financialStatus) {}
}
