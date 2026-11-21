package com.portfolio.ticketing.operation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.ticketing.operation.config.OperationProperties;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OperationJobService {

  private final JdbcTemplate jdbcTemplate;
  private final OperationTransactionExecutor transactionExecutor;
  private final OperationProperties properties;
  private final ObjectMapper objectMapper;

  public OperationJobService(
      JdbcTemplate jdbcTemplate,
      OperationTransactionExecutor transactionExecutor,
      OperationProperties properties,
      ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.transactionExecutor = transactionExecutor;
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  public Optional<UUID> findEffectiveJobIdForEvent(UUID eventId) {
    return jdbcTemplate
        .query(
            """
            SELECT j.id FROM operation_jobs j
            JOIN operation_participations op ON op.id = j.participation_id
            WHERE op.provider_event_id = ? AND j.effective
            """,
            (resultSet, rowNum) -> resultSet.getObject(1, UUID.class),
            eventId)
        .stream()
        .findFirst();
  }

  public List<UUID> findRecoverableJobIds(int batchSize) {
    return jdbcTemplate.query(
        """
        SELECT id FROM operation_jobs
        WHERE effective AND (
            (status = 'PENDING' AND next_attempt_at <= clock_timestamp())
            OR status IN ('CLAIMED', 'APPLIED', 'PROJECTED')
        )
        ORDER BY created_at, id
        LIMIT ?
        """,
        (resultSet, rowNum) -> resultSet.getObject(1, UUID.class),
        batchSize);
  }

  public Optional<OperationClaim> claim(UUID jobId, String workerId) {
    return transactionExecutor.claim(() -> {
      LockedJob job = lockJob(jobId);
      if (!job.effective()
          || job.status().equals("COMPLETED")
          || job.status().equals("FAILED")
          || job.status().equals("REPLACED")) {
        return Optional.empty();
      }

      jdbcTemplate.update(
          """
          UPDATE operation_executions SET status = 'INVALIDATED', updated_at = clock_timestamp()
          WHERE job_id = ? AND status IN ('CLAIMED', 'APPLIED', 'PROJECTED')
            AND lease_expires_at <= clock_timestamp()
          """,
          jobId);
      if (job.status().equals("CLAIMED") && hasActiveClaim(jobId)) {
        return Optional.empty();
      }
      boolean projectionRecovery =
          job.status().equals("APPLIED") || job.status().equals("PROJECTED");
      if (projectionRecovery) {
        jdbcTemplate.update(
            """
            UPDATE operation_executions SET status = 'INVALIDATED', updated_at = clock_timestamp()
            WHERE job_id = ? AND status IN ('APPLIED', 'PROJECTED')
            """,
            jobId);
      }
      if (!projectionRecovery && job.attemptCount() >= properties.getMaxAttempts()) {
        jdbcTemplate.update(
            """
            UPDATE operation_jobs
            SET status = 'FAILED', next_attempt_at = NULL, last_error = 'MAX_ATTEMPTS',
                updated_at = clock_timestamp()
            WHERE id = ?
            """,
            jobId);
        return Optional.empty();
      }

      long fencingToken = job.fencingToken() + 1;
      int attemptCount = projectionRecovery ? job.attemptCount() : job.attemptCount() + 1;
      jdbcTemplate.update(
          """
          UPDATE operation_jobs
          SET status = 'CLAIMED', fencing_token = ?, attempt_count = ?,
              next_attempt_at = NULL, last_error = NULL, updated_at = clock_timestamp()
          WHERE id = ?
          """,
          fencingToken,
          attemptCount,
          jobId);
      UUID executionId =
          jdbcTemplate.queryForObject(
              """
              INSERT INTO operation_executions (
                  job_id, worker_id, fencing_token, lease_expires_at)
              VALUES (?, ?, ?, clock_timestamp() + (? * interval '1 millisecond'))
              RETURNING id
              """,
              UUID.class,
              jobId,
              workerId,
              fencingToken,
              properties.getLeaseDuration().toMillis());
      return Optional.of(
          new OperationClaim(
              job.id(),
              executionId,
              job.participationId(),
              job.eventId(),
              job.operationId(),
              job.orderId(),
              job.payload(),
              job.payloadDigest(),
              attemptCount,
              fencingToken));
    });
  }

  public void recordFailure(OperationClaim claim, RuntimeException failure) {
    transactionExecutor.apply(() -> {
      LockedJobState job = lockJobState(claim.jobId());
      LockedExecution execution = lockExecution(claim.executionId());
      if (!job.effective()
          || job.fencingToken() != claim.fencingToken()
          || !job.status().equals("CLAIMED")
          || execution.fencingToken() != claim.fencingToken()
          || !execution.status().equals("CLAIMED")
          || !execution.leaseExpiresAt().isAfter(databaseNow())) {
        return null;
      }

      jdbcTemplate.update(
          """
          INSERT INTO operation_dispositions (execution_id, disposition_type, payload)
          VALUES (?, 'FAILED', CAST (? AS jsonb))
          """,
          claim.executionId(),
          failurePayload(failure));
      jdbcTemplate.update(
          """
          UPDATE operation_executions SET status = 'FAILED', updated_at = clock_timestamp()
          WHERE id = ?
          """,
          claim.executionId());
      boolean exhausted = claim.attemptCount() >= properties.getMaxAttempts();
      if (exhausted) {
        jdbcTemplate.update(
            """
            UPDATE operation_jobs SET status = 'FAILED', next_attempt_at = NULL,
                last_error = ?, updated_at = clock_timestamp() WHERE id = ?
            """,
            failure.getClass().getSimpleName(),
            claim.jobId());
      } else {
        jdbcTemplate.update(
            """
            UPDATE operation_jobs SET status = 'PENDING',
                next_attempt_at = clock_timestamp() + (? * interval '1 millisecond'),
                last_error = ?, updated_at = clock_timestamp() WHERE id = ?
            """,
            properties.getRetryBackoff().toMillis(),
            failure.getClass().getSimpleName(),
            claim.jobId());
      }
      return null;
    });
  }

  public Instant renewLease(OperationClaim claim) {
    return transactionExecutor.claim(() -> {
      LockedJobState job = lockJobState(claim.jobId());
      LockedExecution execution = lockExecution(claim.executionId());
      if (!job.effective()
          || job.fencingToken() != claim.fencingToken()
          || !job.status().equals("CLAIMED")
          || execution.fencingToken() != claim.fencingToken()
          || !execution.status().equals("CLAIMED")
          || !execution.leaseExpiresAt().isAfter(databaseNow())) {
        throw new OperationFencingException("Cannot renew a stale operation lease");
      }
      return jdbcTemplate
          .queryForObject(
              """
              UPDATE operation_executions
              SET lease_expires_at = clock_timestamp() + (? * interval '1 millisecond'),
                  updated_at = clock_timestamp()
              WHERE id = ?
              RETURNING lease_expires_at
              """,
              OffsetDateTime.class,
              properties.getLeaseDuration().toMillis(),
              claim.executionId())
          .toInstant();
    });
  }

  public UUID replace(UUID jobId) {
    return transactionExecutor.claim(() -> {
      LockedJob job = lockJob(jobId);
      if (!job.effective()) {
        throw new OperationFencingException("Operation job is already replaced");
      }
      jdbcTemplate.update(
          """
          UPDATE operation_jobs
          SET effective = FALSE, status = 'REPLACED', next_attempt_at = NULL,
              updated_at = clock_timestamp()
          WHERE id = ?
          """,
          jobId);
      jdbcTemplate.update(
          """
          UPDATE operation_executions SET status = 'INVALIDATED', updated_at = clock_timestamp()
          WHERE job_id = ? AND status IN ('CLAIMED', 'APPLIED', 'PROJECTED')
          """,
          jobId);
      return jdbcTemplate.queryForObject(
          """
          INSERT INTO operation_jobs (participation_id, generation)
          VALUES (?, ?)
          RETURNING id
          """,
          UUID.class,
          job.participationId(),
          jdbcTemplate.queryForObject(
              "SELECT COALESCE(MAX(generation), 0) + 1 FROM operation_jobs"
                  + " WHERE participation_id = ?",
              Integer.class,
              job.participationId()));
    });
  }

  private LockedJob lockJob(UUID jobId) {
    return jdbcTemplate
        .query(
            """
            SELECT j.id, j.participation_id, pe.id AS event_id, po.id AS operation_id,
                   po.order_id, pe.payload, po.payload_digest, j.status, j.attempt_count,
                   j.fencing_token, j.effective
            FROM operation_jobs j
            JOIN operation_participations op ON op.id = j.participation_id
            JOIN provider_events pe ON pe.id = op.provider_event_id
            JOIN provider_operations po ON po.id = pe.provider_operation_id
            WHERE j.id = ?
            FOR UPDATE OF j
            """,
            (resultSet, rowNum) ->
                new LockedJob(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("participation_id", UUID.class),
                    resultSet.getObject("event_id", UUID.class),
                    resultSet.getObject("operation_id", UUID.class),
                    resultSet.getObject("order_id", UUID.class),
                    resultSet.getString("payload"),
                    resultSet.getString("payload_digest"),
                    resultSet.getString("status"),
                    resultSet.getInt("attempt_count"),
                    resultSet.getLong("fencing_token"),
                    resultSet.getBoolean("effective")),
            jobId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Operation job not found"));
  }

  private LockedJobState lockJobState(UUID jobId) {
    return jdbcTemplate
        .query(
            """
            SELECT status, fencing_token, effective FROM operation_jobs WHERE id = ? FOR UPDATE
            """,
            (resultSet, rowNum) ->
                new LockedJobState(
                    resultSet.getString("status"),
                    resultSet.getLong("fencing_token"),
                    resultSet.getBoolean("effective")),
            jobId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Operation job not found"));
  }

  private LockedExecution lockExecution(UUID executionId) {
    return jdbcTemplate
        .query(
            """
            SELECT status, fencing_token, lease_expires_at
            FROM operation_executions WHERE id = ? FOR UPDATE
            """,
            (resultSet, rowNum) ->
                new LockedExecution(
                    resultSet.getString("status"),
                    resultSet.getLong("fencing_token"),
                    resultSet.getObject("lease_expires_at", OffsetDateTime.class).toInstant()),
            executionId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Operation execution not found"));
  }

  private boolean hasActiveClaim(UUID jobId) {
    return Boolean.TRUE.equals(
        jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM operation_executions
                WHERE job_id = ? AND status = 'CLAIMED' AND lease_expires_at > clock_timestamp()
            )
            """,
            Boolean.class,
            jobId));
  }

  private Instant databaseNow() {
    return jdbcTemplate
        .queryForObject("SELECT clock_timestamp()", OffsetDateTime.class)
        .toInstant();
  }

  private String failurePayload(RuntimeException failure) {
    try {
      return objectMapper.writeValueAsString(Map.of("error", failure.getClass().getSimpleName()));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Could not serialize operation failure", exception);
    }
  }

  private record LockedJob(
      UUID id,
      UUID participationId,
      UUID eventId,
      UUID operationId,
      UUID orderId,
      String payload,
      String payloadDigest,
      String status,
      int attemptCount,
      long fencingToken,
      boolean effective) {}

  private record LockedJobState(String status, long fencingToken, boolean effective) {}

  private record LockedExecution(String status, long fencingToken, Instant leaseExpiresAt) {}
}
