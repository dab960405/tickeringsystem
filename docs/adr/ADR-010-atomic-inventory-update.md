# ADR-010 — Conditional atomic inventory update

- **Status:** ACCEPTED
- **Date:** 2026-11-06
- **Phase:** 3

## Context

Several buyers can attempt to reserve the last units of the same ticket type simultaneously. The system must prevent a negative `available_quantity` and avoid selling the same unit twice. We evaluated three PostgreSQL/JPA concurrency strategies.

## Options considered

| Strategy | Advantages | Disadvantages |
| --- | --- | --- |
| **Conditional atomic UPDATE** | One SQL statement checks and decrements stock; PostgreSQL serializes updates to the row; affected-row count indicates success or insufficient inventory; no read/write race window; fewer round trips. | Business logic in SQL, precise interpretation of 0/1 affected rows, additional queries for other related invariants. |
| **Pessimistic SELECT FOR UPDATE** | Validation under an explicit row lock; suitable for multi-row invariants; concurrent writers wait. | Holds locks across application processing, increases contention/wait time, risks deadlocks under inconsistent lock ordering, typically requires another read. |
| **Optimistic @Version** | Avoids locking during reads; efficient when contention is low; detects concurrent updates and supports retry. | Hot inventory creates frequent conflicts and retries; stock bounds must still be enforced; retry policy is required. |

## Decision

Reserve inventory through a **single conditional atomic update**:

```sql
UPDATE ticket_types
SET available_quantity = available_quantity - :quantity,
    version = version + 1
WHERE id = :id
  AND available_quantity >= :quantity;
```

Exactly one affected row means success. Zero means that the ticket type does not exist or lacks sufficient inventory; the service resolves the response. Stock restoration is also conditional and must not exceed `total_quantity`.

[ReservationConcurrencyIT](../../ticketing-api/src/test/java/com/portfolio/ticketing/reservation/ReservationConcurrencyIT.java) verifies the high-contention scenario against actual PostgreSQL.

## Consequences

- Validation and stock decrement are one indivisible database operation.
- The `version` column increments in SQL; this decision does not rely on JPA entity read/modify/write for inventory.
- Pessimistic locks remain appropriate for transitions that must inspect an existing entity (for example, payment confirmation).
- Concurrency stability must be proved with Testcontainers and real CI execution; an unexecuted test is not evidence.
