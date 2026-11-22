# ADR-012 — Concurrent reservation expiration using FOR UPDATE SKIP LOCKED

- **Status:** ACCEPTED
- **Date:** 2026-11-06
- **Phase:** 3

## Context

Scheduled expiration processes overdue reservations and returns held units to inventory. Multiple API instances can run workers simultaneously; plain SELECT might select the same reservation for two workers and trigger duplicate release attempts.

## Decision

Claim batches of at most 100 reservations using PostgreSQL logic equivalent to:

```sql
SELECT *
FROM reservations
WHERE status = 'ACTIVE'
  AND expires_at < :now
ORDER BY expires_at
LIMIT :limit
FOR UPDATE SKIP LOCKED;
```

`ReservationRepository.findExpiredForUpdate` runs inside the job transaction. `SKIP LOCKED` makes a worker skip rows already claimed by another worker rather than block on those same candidates.

For each claimed row the service conditionally transitions the reservation:

```sql
UPDATE reservations
SET status = 'EXPIRED', updated_at = CURRENT_TIMESTAMP
WHERE id = :id
  AND status = 'ACTIVE'
  AND expires_at < :now;
```

Release inventory **only if exactly one row was updated**. Candidate claiming, state change and stock restoration belong to one database transaction.

## Alternatives rejected

- A single scheduler simplifies coordination but introduces a single execution bottleneck/failure point.
- Plain SELECT allows multiple workers to select the same reservation.
- Locking without SKIP LOCKED prevents duplication but unnecessarily makes other workers wait for blocked rows.
- State update and stock release in separate transactions create a partial-commit inconsistency window.

## Consequences

- Concurrent workers process disjoint sets of locked rows.
- A crash before commit rolls back the state and stock change, allowing future execution.
- Conditional transitions prevent a second logical inventory release.
- Partial index `ix_reservations_active_expires_at` bounds candidate scanning.
- Batch size is 100 and scheduling interval is configurable through `ticketing.reservation.expiration-fixed-delay-ms`.
- Keep transactions short. Test `ReservationExpirationIT` and multi-worker execution against PostgreSQL in Docker/CI.
- **Later evolution:** [ADR-016](ADR-016-global-lock-order.md) defines the global lock-order requirements, and later processors may use a discovery scan followed by revalidation under E → T → R locks. This original decision remains historical context; do not reintroduce an R → T lock inversion based on its example query.
