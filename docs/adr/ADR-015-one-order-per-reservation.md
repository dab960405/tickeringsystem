# ADR-015 — Exactly one order per reservation

- **Status:** ACCEPTED
- **Date:** 2026-11-12
- **Phase:** 3

## Context

The previous model could create two payable orders for the same reservation. Both might issue tickets against the same inventory. A `FAILED` webhook for the second order could also cancel/release the reservation used by the first, even if that first order should remain valid.

## Decision

- `OrderService.create` checks whether the reservation already has an order; if so, reject creation as a conflict.
- Flyway V11 adds `ux_orders_reservation_id`, a **UNIQUE index over orders(reservation_id)**, as the last defense against simultaneous inserts that both passed the service check.
- Map `DataIntegrityViolationException` to HTTP **409 Conflict**, not HTTP 500.
- With the **same** `Idempotency-Key` and reservation in a truly simultaneous race, the CI job's uniqueness error suggests a 409 but does **not** capture the exact individual response codes. The test accepts 200 or 409, as documented in [ADR-011's concurrent race](ADR-011-order-and-webhook-idempotency.md#concurrent-race-same-key-and-same-reservation).

## Consequences

- The index covers **all orders, including canceled orders**; it is not a partial index by status.
- A reservation with a canceled order cannot be reused for a new order; `cancelPending` releases that reservation while preserving the canceled order.
- Database uniqueness protects order/reservation identity and inventory under concurrent requests.
- The later canceled-reservation expiration remediation, REV-DEV-02, changes how a still-pending order is resolved after direct reservation cancellation; it does **not** weaken this unique constraint.
