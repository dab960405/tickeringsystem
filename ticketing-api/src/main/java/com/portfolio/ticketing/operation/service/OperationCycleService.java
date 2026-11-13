package com.portfolio.ticketing.operation.service;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OperationCycleService {

  private final JdbcTemplate jdbcTemplate;

  public OperationCycleService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void register(UUID eventId, UUID orderId) {
    UUID cycleId = registerInitialCycle(orderId);
    List<UUID> participation =
        jdbcTemplate.query(
            """
            INSERT INTO operation_participations (cycle_id, provider_event_id)
            VALUES (?, ?)
            ON CONFLICT (cycle_id, provider_event_id) DO NOTHING
            RETURNING id
            """,
            (resultSet, rowNum) -> resultSet.getObject(1, UUID.class),
            cycleId,
            eventId);
    if (participation.isEmpty()) {
      return;
    }
    UUID participationId = participation.getFirst();
    jdbcTemplate.update(
        "INSERT INTO operation_jobs (participation_id, generation) VALUES (?, 1)", participationId);
    jdbcTemplate.update(
        """
        INSERT INTO operation_projections (
            participation_id, technical_status, commercial_status, financial_status, fencing_token)
        VALUES (?, 'PENDING', 'PENDING', 'PENDING', 0)
        """,
        participationId);
  }

  private UUID registerInitialCycle(UUID orderId) {
    List<UUID> inserted =
        jdbcTemplate.query(
            """
            INSERT INTO operation_cycles (aggregate_type, aggregate_id, cycle_number)
            VALUES ('ORDER', ?, 1)
            ON CONFLICT (aggregate_type, aggregate_id, cycle_number) DO NOTHING
            RETURNING id
            """,
            (resultSet, rowNum) -> resultSet.getObject(1, UUID.class),
            orderId);
    if (!inserted.isEmpty()) {
      return inserted.getFirst();
    }
    return jdbcTemplate.queryForObject(
        "SELECT id FROM operation_cycles WHERE aggregate_type = 'ORDER'"
            + " AND aggregate_id = ? AND cycle_number = 1",
        UUID.class,
        orderId);
  }
}
