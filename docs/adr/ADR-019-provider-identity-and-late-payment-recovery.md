# ADR-019 — Provider identity, semantic idempotency and late-payment recovery

- **Status:** ACCEPTED
- **Date:** 2026-11-13
- **Phase:** 4
- **Addresses:** H79 and H80
- **Relation:** Evolves ADR-011 and supersedes the earlier immediate local refund transition in ADR-009.
- **H84-01 clarification (2026-11-14):** distinguishes semantic operation identity from an individual execution's commercial effect without altering V14/V15.

## Three independent identities

1. **Received provider event:** `(provider_code, merchant_account_id, provider_event_id)` in `provider_events`.
2. **Provider operation:** `(provider_code, merchant_account_id, identity_namespace, provider_operation_id)` in `provider_operations`.
3. **Semantic application:** `(application_scope, operation_kind, idempotency_key)` in `semantic_applications`.

Each table enforces its own UNIQUE constraint. Events reference their operation; semantic applications reference an operation and store the SHA-256 payload digest. Receipt, technical execution and commercial application are independently auditable.

For the simulated provider, `provider_code=SIMULATED`, `merchant_account_id=default` and `identity_namespace=payments.v1` are configurable. Financial evidence requires `providerEventId`, `providerOperationId`, `operationKind`, `providerReference`, amount and currency under the relevant operation/state contract. HMAC authenticates the raw body but does not replace persistent identities.

`PAYMENT_REFUND` accepts `REFUNDED` as confirmation, `FAILED` as failed refund evidence and `PENDING` as an unresolved reconciliation state. Neither FAILED nor PENDING may change payment status to REFUNDED.

## Operation catalog and digest

Supported kinds: `PAYMENT_CAPTURE`, `PAYMENT_FAILURE`, `PAYMENT_REFUND`. Semantic scope: `order:{orderId}`. Stable semantic idempotency key: `providerOperationId`. The canonical digest includes order, operation kind, provider reference, amount and currency.

- **Same operation identity, same digest:** evidence is known, but a **different signed event** must be validated against the **current** commercial state. It may apply a legitimate progression (e.g., PENDING → REFUNDED) or yield NO_OP.
- **Same identity, different digest:** HTTP 409; no commercial effect.
- **New event for known operation:** new received evidence, shared unique semantic application identity.
- **New cycle/participation/worker/execution:** no change to the operation's semantic identity.

### Execution disposition versus commercial state (H84-01 clarification)

A row in `semantic_applications` establishes one semantic identity per provider operation. Its insertion/replay/conflict **does not alone determine** whether a received event changed business state. `OrderPaymentService.processWithOutcome` returns `PaymentEffectOutcome.APPLIED` if a valid transition changes commercial/financial state, and `NO_OP` otherwise. `PaymentOperationService` records the disposition and JSON result in append-only `operation_dispositions` **for each technical execution**.

A single refund operation with the same provider reference, amount and currency may yield APPLIED for PENDING and later APPLIED for REFUNDED, through **distinct signed provider events**. A repeat equivalent REFUNDED event without a new effect returns NO_OP. A REFUNDED notification that cannot yet produce a refund remains immutable evidence with NO_OP; it does not constitute a completed refund.

The governing acquisition subsequence is E → J → XA → O → P → EN → IS → K from ADR-016. No schema/unique identity relaxation is implied.

**Additional verified distinction:** recovering the **same technical job** after an APPLY commit is not a new event, even if a worker obtains a new claim. It must reuse the prior durable business outcome instead of applying the old signed event against a later state. REV-DEV-01 exposed a violation of this requirement; [CI #324](https://github.com/dab960405/tickeringsystem/actions/runs/44981366848) contains the correction and targeted regression.

### Residual contract proposal: ADR-022

The stable digest includes `providerReference`. If a refund operation initially reports PENDING without a reference, then later REFUNDED with a newly present reference, the digest changes and correctly conflicts. The simulated-provider policy for stable references and early REFUNDED before capture is proposed in [ADR-022](ADR-022-simulated-refund-evidence-contract.md), **not formally approved**. This H84-01 limitation must be decided before the Phase 4 formal close.

Event, operation, semantic application and disposition evidence remain append-only via triggers rejecting UPDATE/DELETE. Mutable technical state belongs in jobs/executions/projections and business entities.

## H79: late-payment policy

### Normal capture

A reservation is active only if `expires_at > clock_timestamp()` after T → R locks. The SUCCEEDED evidence must match order/payment amount and currency exactly. Capture moves inventory disposition HELD → CONSUMED and issues **one initial ticket generation**.

### Reservation expiration and stock release

Expiration/cancellation transitions HELD → RELEASED and returns held stock **once**. Confirming a valid reservation consumes HELD without decrementing stock a second time. Recovering a RELEASED reservation must first decrement sufficient stock before transitioning to CONSUMED.

### Late successful capture

Recovery of expired/released inventory is allowed only when:

- Order/payment belong to the same reservation and user.
- The provider supplied valid SUCCEEDED financial evidence.
- The event has not started.
- No more than 15 minutes elapsed after `expires_at` (configurable recovery grace).
- Sufficient stock exists for the **entire** purchase quantity.

When eligible, recover atomically and issue the initial generation. Otherwise (insufficient stock, grace exceeded, event started or explicitly cancelled reservation), **do not issue tickets and do not claim a refund happened**:

- Persist the actual financial SUCCEEDED capture.
- Preserve a compatible commercial order status (new pending orders can become PAID; pre-existing CANCELLED orders must not silently become PAID).
- Mark `fulfillment_status=UNFULFILLABLE` and `refund_status=REQUIRED` to represent the outstanding obligation.

### Refunds require provider evidence

Cancelling a paid order before the event creates `refund_status=REQUESTED`, **not** `payment.status=REFUNDED`. Only signed `PAYMENT_REFUND` evidence with valid reference, amount and currency confirms refund: payment REFUNDED, order refund status CONFIRMED, tickets cancelled and order REFUNDED when prior commercial status was PAID.

Out-of-order financial events remain received evidence but must not overwrite success with failure or confirmed refund with later capture. Digest/amount conflicts must not modify business state.

## Consequences

- Missing late-capture recovery or an invented successful refund violates H79; this remains a close gate.
- No real payment provider is integrated; signed simulated evidence supports local tests without external financial services.
- ADR-009 still forbids automatic stock restoration on refund. Only its earlier immediate local refund-state transition was superseded.
