# ADR-011 — Order creation and payment webhook idempotency

- **Status:** ACCEPTED
- **Date:** 2026-11-06
- **Phase:** 3

## Context

Clients retry after timeouts and lost HTTP responses; payment simulators can redeliver callbacks. Without idempotency, a retry could duplicate orders/payments/tickets or apply incompatible transitions.

## Decision: orders

- Clients must supply a nonempty `Idempotency-Key` up to 100 characters.
- PostgreSQL enforces `UNIQUE(user_id, idempotency_key)` independently of any application precheck.
- Reusing the same key for the **same reservation** returns the existing order without creating another payment.
- Reusing a key for a **different reservation** returns HTTP 409; its meaning is immutable.
- `order_items.unit_price`, quantity and currency are snapshots from order creation; retries do not recalculate prices from the mutable catalog.
- PostgreSQL's uniqueness check is the final protection when simultaneous requests pass application prechecks before either inserts. Translate the collision to an appropriate conflict or reread.

### Concurrent race: same key and same reservation

Historical CI #116 logs suggest one simultaneous request encountered the `uq_orders_user_idempotency_key` uniqueness violation, potentially returning HTTP 409 rather than the expected replay HTTP 200. The log captures the constraint error but **not both HTTP response codes**, so 409 is inferred rather than directly observed. The concurrency test explicitly accepts HTTP 200 **or** 409 for the concurrent request. A later retry after the winning request completes returns 200 with the existing order.

## Decision: signed simulated payment webhooks

- Require `X-Signature`, the hexadecimal HMAC-SHA256 of the **exact original HTTP request body** and shared secret; use constant-time comparison. Reject altered bodies/invalid signatures before deserialization or effects.
- Apply order, payment and reservation transitions within a coordinated transaction. A valid success confirms a recoverable reservation, marks order/payment as paid and issues tickets. If late capture cannot be fulfilled under [ADR-019](ADR-019-provider-identity-and-late-payment-recovery.md), record financial `SUCCEEDED`, mark fulfillment `UNFULFILLABLE` and refund obligation `REQUIRED` without issuing tickets or asserting a refund happened.
- A repeated successful callback for an already paid order is a no-op only with a compatible provider reference. Conflicting references fail; capture after confirmed refund must not regress that state.
- A repeated FAILED callback is a no-op for an already cancelled/failed order; late failure never overwrites confirmed capture or refund.
- From Flyway V13, webhook DTOs require `providerEventId`, `providerOperationId`, `operationKind`, `amount` and `currency`. Exact amount/currency matching is required. Only a valid `PAYMENT_REFUND` in status `REFUNDED` with financial reference can confirm a refund. A local cancellation merely creates `refund_status=REQUESTED`.
- Database constraints include `UNIQUE(orders.user_id, orders.idempotency_key)`, `UNIQUE(payments.order_id)`, and `UNIQUE(payments.provider_reference)`.

## Consequences and evolution

- Replays of compatible order requests and callbacks do not duplicate commercial effects.
- HMAC authenticates integrity/origin, but does not itself provide event identity or prevent replay.
- **Historical note:** At the time of this ADR, persistent three-identity inbox deduplication was still pending; it was later implemented through Flyway V14/V15 and [ADR-019](ADR-019-provider-identity-and-late-payment-recovery.md). Do not describe this historical deferral as missing from the current main branch.
- The durable inbox uses unique constraints across provider event, provider operation and semantic application identity with digests and retention rules; extending the DTO alone would not have closed H80.
- Ticket issuance also checks existing tickets for order items, but database constraints and transaction boundaries remain essential under concurrency.
