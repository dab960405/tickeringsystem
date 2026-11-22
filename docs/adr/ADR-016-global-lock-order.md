# ADR-016 — Global lock order and ticket-inventory-before-reservation protocol

- **Status:** ACCEPTED
- **Date:** 2026-11-13
- **Phase:** 4
- **Relation:** Complements ADR-010 and supersedes ADR-012's earlier implied lock-acquisition strategy while retaining `SKIP LOCKED`.

## Context

Confirmation, cancellation and expiration could lock a reservation before modifying its inventory (`R → T`), whereas other paths decrement inventory before touching the reservation (`T → R`). Concurrent instances can deadlock when those paths overlap. Decisions based on data read **before** a lock can also become stale.

## Decision: global order

The canonical order is:

`E → D → PE → J → XA → V → N → CR → C → DS → T → R → O → P → I → U → EN → IS → K`

| Symbol | Table / entity |
| --- | --- |
| E | events |
| PE | provider_events |
| J | operation_jobs |
| XA | operation_executions |
| V | venues |
| CR | operation_cycles |
| C | operation_participations |
| DS | operation_dispositions |
| T | ticket_types |
| R | reservations |
| O | orders |
| P | payments |
| I | order_items |
| U | commercial_units |
| EN | tickets |
| IS | semantic_applications |
| K | operation_projections |

Symbols D and N remain reserved and **have no assigned table during this phase**. Do not invent meanings for them.

A transaction may acquire row locks only along a **subsequence of the global order**; not every transaction must acquire all locks. Unlocked discovery queries are not lock acquisitions, but all data used for decisions must be rechecked **after** locks. New-row inserts do not replace verification of already-existing rows.

## Implicit FK locks and Hibernate flush

PostgreSQL may implicitly acquire parent-row locks to verify foreign keys. A transaction cannot ignore these acquisitions just because its code does not explicitly update that table.

- Creating an order first obtains E → T → R, then inserts O/P/I. The T lock matters because `order_items.ticket_type_id` references `ticket_types`; otherwise an ORM flush might hold R and then acquire T late.
- Reissuing tickets first connects the next generation to U and emits new EN; cancellation of old-generation EN is deferred. This prevents Hibernate dirty flushing of EN before implicitly acquiring the U FK locks. The corrected production sequence supersedes an earlier narrative order in ADR-017.
- Repeating an UPDATE on an already locked J/XA row does not constitute a new lock acquisition; the ordering is evaluated when the row lock is first taken.

The detailed audit and executable evidence are recorded in [F4-03](../phase-4/F4-03-lock-audit.md).

## Reservation and inventory protocol

1. Discover candidate identifiers **without locks**; expiration batch maximum 100.
2. Collect, sort and deduplicate ticket-type UUIDs.
3. Lock T in ascending order using `SELECT ... FOR UPDATE`.
4. Lock R in ascending order; discovery/recovery workers use `FOR UPDATE SKIP LOCKED` as appropriate.
5. Revalidate owner, status, quantity and `reservation.ticket_type_id`.
6. Obtain PostgreSQL `clock_timestamp()` **after the locks**.
7. A reservation is valid only if `expires_at > evaluation_time`; when `expires_at <= evaluation_time`, it is expired.
8. Mutate reservation and inventory within the **same transaction**, rolling back on any inconsistency.

Creation reserves/decrements T before inserting R. Order creation maintains T before R to satisfy the order item FK. Cancellation, release, confirmation, recovery and expiration follow T → R. The expiration decision must not use a timestamp sampled before locks.

## Inventory safeguards

The conditional stock UPDATE in ADR-010 remains the final lower-bound defense. A T lock coordinates multi-entity operations; it must not be replaced by Java `synchronized`. Stock restoration is bounded by `total_quantity` and only occurs on a valid inventory-disposition transition.

## Worker transaction boundaries

TX-CLAIM and TX-APPLY lock J before XA. Business application then follows T → R → O → P → I → U → EN → IS → K. TX-PROJECT must not acquire earlier business locks again: it projects already-committed evidence and locks K last.

## Consequences and validation

- ADR-012's `SKIP LOCKED` remains the multi-worker scheduling mechanism, but its example query alone is no longer the full protocol.
- Keep row locks inside bounded transactions with configured lock and statement timeouts.
- Test confirmation/expiration/cancellation races, two-worker contention and rollback with real PostgreSQL.
- Absence of a deadlock in finite tests does not mathematically prove order correctness; explicit repository locks, SQL/FK inspection and invariant tests are also required.
