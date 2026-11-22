# Pending architectural and contract decisions — Phases 3 and 4

**Status:** Project-owner Phase 3 decisions are recorded in the superseding 2026-11-22 section below. Other decisions remain pending. The register does **not** substitute for an independent Tech Lead verdict or a formal Phase 3 close.

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
| BACK-01 (**technically corrected; no new architecture approval presumed**) | Case-insensitive precheck was not serialized; `UNIQUE(event_id,name)` alone allowed concurrent `VIP`/`vip` | Existing `EventLockService.lockExclusive(E)` now protects both ticket-type creation and update, before taking T or checking the name. Keep V4 intact and preserve the E→T ordering. | [CI #342 RED](https://github.com/dab960405/tickeringsystem/actions/runs/44986036343) 5/9 expected failures; [CI #344 GREEN](https://github.com/dab960405/tickeringsystem/actions/runs/44986504339) 9/9, 191 IT. New create/rename tests included in the subsequent source update. Performance or global case-insensitive index design requires separate review. |
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

## Implementation follow-up (2026-11-22)

BACK-01 has a demonstrated RED→GREEN regression on real disposable PostgreSQL without changing applied migrations. The existing exclusive event lock was reused on both create and update; this is a narrowly scoped correctness correction, **not** acceptance of a blanket architectural exception. AUD-03/05/10/12 and ADR-022/024 still require explicit independent decisions. [Engineering evidence](READINESS-VERIFICATION-2026-11-22.md).


**Exact post-remediation code verification:** [CI #348](https://github.com/dab960405/tickeringsystem/actions/runs/44986963218), `main@e318c46f0623ee2cdebaf2dbe8241b8b7ddf78d7`, completed **SUCCESS**: 410 Java unit tests, 195 PostgreSQL/Redis Testcontainers integrations (including 13/13 ticket-type name races), Maven `clean verify`, Spotless and JaCoCo; Angular 19/19 with a production build, Prettier and **real Google Chrome** direct/deep-route independent reload and legacy bookmark with JS/CSS HTTP 200; Python frontend 11/11 and scripts 3/3. This is GitHub Actions evidence, **not** a fresh local Docker or full paid-order browser E2E run. Any later documentation commit requires separate same-SHA CI verification; neither test success nor this report grants Tech Lead acceptance.

## Superseding Phase 3 project-owner decisions — 2026-11-22

The project owner expressly authorized all architectural, contract and code decisions required for Phase 3 remediation and closure. These are **owner dispositions**, not an assertion that an independent Tech Lead has already reviewed the result. Earlier rows are historical findings, not a competing present-tense decision.

| Finding | Owner disposition for Phase 3 | Evidence and residual control |
| --- | --- | --- |
| **AUD-03** | **ACCEPTED AS NONBLOCKING FOR PHASE 3.** Retain current `201 Created` bodies and `Location` primary-resource URIs, even where no GET handler exists. A URI may identify a resource independently of a retrieval affordance; do not invent private GET authorization semantics without a business request. Document this limitation for API consumers. | Current `AuthControllerTest` and `ReservationControllerTest` assert emitted locations. [RFC 9110 §10.2.2](https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.2) defines `Location`; it does not mandate a GET endpoint for the created resource. |
| **AUD-05 / ADR-024** | **OWNER APPROVED, STRICTLY BOUNDED.** `EventCancellationService.cancel(UUID)` may coordinate the cross-module PostgreSQL transaction under ADR-016. No general repository-access exemption or financial transition is authorized. | [ADR-024](../adr/ADR-024-event-cancellation-transactional-exception.md) and 10/10 `EventGuardConcurrencyIT` within [CI #349](https://github.com/dab960405/tickeringsystem/actions/runs/44987202435); independent review in issue #5 remains pending. |
| **AUD-05 / ADR-026** | **OWNER APPROVED, STRICTLY BOUNDED.** `OrderExpirationProcessor.expire` may coordinate the reservation and order modules inside its current E → T → R → O PostgreSQL transaction, and the two expiration discovery queries may join across tables **only for candidate discovery**. All mutable state requires locked revalidation. | [ADR-026](../adr/ADR-026-bounded-phase-3-cross-module-coordination.md); `OrderPaymentFlowIT`, `ReservationExpirationIT`, and PostgreSQL tests in CI #349. Phase 4-specific boundaries remain undecided here. |
| **AUD-10** | **IMPLEMENTED AND CI-VERIFIED FOR SYNCHRONOUS HTTP REQUEST DISPATCH.** Sanitize inbound `X-Correlation-Id` (ASCII letters/digits then safe punctuation, length 1–64), otherwise use a new UUID; return it to the caller and put it in MDC only within the request-thread filter scope, restoring prior MDC in a `finally` block. Expose and allow the CORS header, display it in application logs. Async worker/Kafka propagation is not part of Phase 3. | `CorrelationIdFilter`, `CorrelationIdFilterTest`, `GlobalExceptionHandlerTest`, `SecurityConfig`, `application.yml`. [CI #350](https://github.com/dab960405/tickeringsystem/actions/runs/44988342999) reproduced a RED compilation before the filter. [CI #351](https://github.com/dab960405/tickeringsystem/actions/runs/44988418373) executed 410 API units and 195 IT green but failed Spotless wrapping; [CI #352](https://github.com/dab960405/tickeringsystem/actions/runs/44988647465) passed all Java, Angular/Chrome and Python jobs on exact `main@ff18f1ef59f9d7b92aa607f7ddb04a199bbad1db` (410 API units + 6 other Java units + 195 PostgreSQL/Redis integrations; Spotless/JaCoCo). |
| **AUD-12 / H78** | **NOT A PHASE 3 CLOSURE BLOCKER; NO BUSINESS CONTRACT CHANGE.** Subsequent operation cycles are a Phase 4 ADR-018/H78 business requirement to recover/verify against the original accepted specification. Do **not** claim cycles beyond the initial one are implemented, or silently narrow Phase 4 criteria. | `OperationCycleService.registerInitialCycle`, [ADR-018](../adr/ADR-018-operation-cycles-and-worker-recovery.md); Phase 4 must issue an explicit H78 scope verdict and tests before its own closure. |

**Independent conformance gate remains open:** Review the source and bounded exception matrix at the final green CI SHA, inspect code-specific transaction/lock evidence and issue a technical acceptance or concrete rejection in [#5](https://github.com/dab960405/tickeringsystem/issues/5). The project owner has authorized the necessary **owner decisions**; this does not impersonate the Tech Lead. Phase 3 is **not marked formally closed** until that gate is satisfied. Phase 4 and Phase 5 remain independently gated.

External primary guidance: [Spring 6.2 REQUIRED propagation](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/tx-propagation.html), [PostgreSQL 17 locking](https://www.postgresql.org/docs/17/explicit-locking.html), [Spring Modulith 1.4 module boundaries](https://docs.spring.io/spring-modulith/reference/1.4/verification.html), [Spring 6.2 OncePerRequestFilter](https://docs.spring.io/spring-framework/docs/6.2.12/javadoc-api/org/springframework/web/filter/OncePerRequestFilter.html), [Logback MDC](https://logback.qos.ch/manual/mdc.html), [OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html), [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.2).
