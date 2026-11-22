# Pending architectural and contract decisions — Phases 3 and 4

**Status:** Developer-prepared decision register; **NOT APPROVED**. This document does not close any finding, ADR or phase and is not a substitute for the Tech Lead's written verdict.

**Repository:** `dab960405/tickeringsystem`; working branch `main` only. The baseline in the independent readiness audit is `236b31f46534f65c1249ff1f4cbd25586dc4938b`. Subsequent commits corrected business recovery, input validation, frontend races and worker clock behavior; **capture a new exact SHA and green CI before any verdict**.

**Governance:** Preserve historical H75–H84 severities; the original per-finding severity source was not available in the reviewed project files. New findings have proposed severity only. Approved ADRs are not retroactively rewritten. Source references here are links to existing code and review dossiers, not a claim of exhaustive formal verification.

## Decisions requiring a Tech Lead or owner verdict

| ID | Current implementation and concern | Recommended scope-limited decision | Approval and evidence gate |
| --- | --- | --- | --- |
| AUD-03 | Some HTTP 201 `Location` values identify created resources without a corresponding GET endpoint | Decide per resource whether the header remains a valid identifier or needs a compatible resource URI; do not add private GET routes by default | API owner/Tech Lead decision; RFC 9110 `10.2.2; HTTP controller and authorization tests |
| AUD-05 | Direct cross-module persistence occurs in multiple coordinators beyond `EventCancellationService` | Permit only specifically documented coordination or introduce module-owned application interfaces without changing the physical TX or lock order. No blanket exemption | Tech Lead; dependency matrix below, ADR-016/024 and concurrency regressions |
| AUD-10 | `X-Correlation-Id` is exposed in CORS but there is no confirmed HTTP request MDC lifecycle | Decide minimum HTTP header generation/validation/response and MDC cleanup in Phases 0–4 versus a documented explicit deferral. Kafka propagation belongs to Phase 5 | Owner/API contract verdict and tests for concurrent request isolation and untrusted header input |
| AUD-12 | Production creates only the initial operation cycle; test fixtures can create later cycles through SQL | Recover the original H78 business criterion before adding APIs or workers; formally accept initial-cycle scope if appropriate | Tech Lead decision grounded in H78 and ADR-018, not schema speculation |
| ADR-024 | Event cancellation coordinates E/T/R/O directly in one TX | Recommend narrow exception specifically for `EventCancellationService.cancel(UUID)`, conditional on broader AUD-05 review | Tech Lead issue [#5](https://github.com/dab960405/tickeringsystem/issues/5), PostgreSQL rollback and lock tests; formal owner closure separately |
| ADR-022 / H84-01 | Simulated refund identity and immutable provider reference; early REFUNDED can be re-evaluated only by another valid provider event | Accept minimum SIMULATED contract, **after** proving same-job APPLY/PROJECT recovery never re-applies the business callback | Tech Lead issue [#6](https://github.com/dab960405/tickeringsystem/issues/6), OperationWorkerIT and OrderPaymentFlowIT, formal Phase 3 closure prerequisite |
| BACK-01 | `existsBy...IgnoreCase` precheck vs case-sensitive UNIQUE(event_id,name), shared E lock | Reproduce competing name create/rename in PostgreSQL before choosing exclusive E lock on every mutation or an additive case-normalized unique index | Contract and performance comparison; additive DB migration needs explicit authorization |
| BACK-04 | Expiration candidate discovery previously used JVM time, whereas locked revalidation uses PostgreSQL time | Prefer PostgreSQL for both discovery and locked revalidation, preserving bounded scan and skip-locked semantics | Red/green regression and successful CI against exact change SHA |

## Cross-module access inventory (AUD-05)

These are **known coordination boundaries to decide**, not automatically bugs.

| Component | Cross-module access | Decision to make and invariant to retain |
| --- | --- | --- |
| `event/service/EventCancellationService` | Reservation and order repositories; inventory/order SQL | Approve ADR-024's narrow E→T→R→O transaction or a tested module-owned service alternative |
| `order/service/OrderExpirationProcessor` | `ReservationRepository` and reservation state | Define reservation-owned operation called inside same TX; preserve T→R and exact-once release, or explicitly approve a narrow exception |
| `payment/service/ProviderEventInboxService` | SQL existence check on orders | Decide whether a read-only owner service interface is needed during RX without changing evidence identity or TX scope |
| `operation/service/PaymentOperationService` | Cross-module discovery and order/payment snapshots | Treat discovery/read-model queries separately from writes; preserve claim fencing and apply/project phases |
| `operation/service/OperationJobService` | Provider event/operation joins while claiming jobs | Specify stable immutable evidence access under the existing job lock J |
| `order/repository/OrderRepository` | Expired-order candidate joins to reservations/ticket types | Approve a narrow read-only discovery query or move to an owner-defined query contract without changing under-lock revalidation |
| `reservation/repository/ReservationRepository` | Expired-reservation candidate join to ticket types | Preserve candidate discovery only; PostgreSQL transaction still revalidates after E/T/R locks |

**Non-negotiable:** unchanged physical PostgreSQL transaction boundaries where required, ADR-016 global lock order (including implicit FK locks), revalidation of stale entities after locks, durable RX/CLAIM/APPLY/PROJECT outcomes, append-only evidence, fencing and after-commit Redis behavior.

## Technical source cross-check

- [Spring Framework 6.2 — declarative transactions and propagation](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/tx-propagation.html).
- [PostgreSQL 17 — locking and deadlocks](https://www.postgresql.org/docs/17/explicit-locking.html).
- [PostgreSQL 17 — actual database time](https://www.postgresql.org/docs/17/functions-datetime.html).
- [RFC 9110 — Location field semantics](https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.2).
- [Spring Framework — transaction synchronization after commit](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html).

These references support the mechanisms; **none grants an architectural or business-rule approval**.

## Required verdict template — Phase 3 (draft, not posted)

> Review `main` at the final SHA and the updated [F3-01 dossier](../phase-3/F3-01-closure-review.md). Accept or reject ADR-024 after inspecting the additional AUD-05 persistence boundaries. Confirm whether the Phase 3 acceptance matrix and the relevant findings meet the agreed criteria, identify any remaining blockers, and issue an explicit technical verdict. The owner must separately authorize formal Phase 3 closure.

## Required verdict template — Phase 4 (draft, not posted)

> After formal Phase 3 closure, review `main` at the final SHA, [F4-05 dossier](../phase-4/F4-05-closure-review.md), and ADR-022/H84-01. Verify Redis requirements and H75–H84, including the new durable same-job recovery regression. Accept or reject the proposed SIMULATED evidence contract and issue an explicit technical verdict. The owner must separately authorize formal Phase 4 closure.

## Phase 5 hard stop

All Kafka producers/topics/consumers, functional outbox relay, notification persistence in MongoDB and real notification delivery are **blocked**. Existing scaffolding and proposed [ADR-023](../adr/ADR-023-phase-5-outbox-and-notification-delivery.md) do not count as implemented functionality. Only a readiness report may be prepared pending formal Phase 3 and 4 closure and separate Phase 5 authorization.
