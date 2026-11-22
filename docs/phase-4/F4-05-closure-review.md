# F4-05 — Phase 4 final technical acceptance dossier

- **Formal status:** OPEN; independent Tech Lead verdict and project-owner closure not yet recorded.
- **Current branch:** `main` only; historic `dev` SHA/CI references below identify historical evidence, not instructions to restore that branch.
- **Verified baseline:** `main@236b31f46534f65c1249ff1f4cbd25586dc4938b`, [CI #322](https://github.com/dab960405/tickeringsystem/actions/runs/44974013851): 407 Java unit tests, 179 integrations, Spotless and JaCoCo, SUCCESS.
- **Recovery correctness:** `main@11dab6d00d8298024a2d10aee4857a0de11f236d`, [CI #324](https://github.com/dab960405/tickeringsystem/actions/runs/44981366848): 407 unit and 181 integration tests, SUCCESS. Prevents business reapplication on same-job projection recovery and resolves pending orders with cancelled reservations.
- **Input/log hardening:** `main@c1368a3b4773ca41f26a0b604bf65122b6771b3c`, [CI #325](https://github.com/dab960405/tickeringsystem/actions/runs/44981594500), SUCCESS.
- **Issue awaiting verdict:** [#6 — Phase 4, ADR-022 and H84-01](https://github.com/dab960405/tickeringsystem/issues/6).
- **Dependency:** Phase 3's [F3-01 dossier](../phase-3/F3-01-closure-review.md) and [issue #5](https://github.com/dab960405/tickeringsystem/issues/5) must have an independent favorable verdict and **formal closure** first.
- **Scope:** Original Redis, TTL, cache invalidation and rate limits; additional H75–H84 integrity/recovery findings, ADR-016–020, proposed ADR-022, Flyway schema V1–V16.

## A. Original Phase 4 requirements

| Criterion | Mechanism | Evidence |
| --- | --- | --- |
| Cache-aside and cache hits | Redis metadata for events and ticket types | `EventCatalogCacheIT`, `TicketTypeCatalogCacheIT` |
| TTL and invalidation | Configurable TTL, after-commit invalidation, bounded stale data during Redis outage | ADR-013/014/020, cache integration tests and `RedisOutageIT` |
| Reservation TTL projection | Redis projection based on database-authoritative reservation expiry | `ReservationRedisProjectionIT` |
| Consistent live inventory | PostgreSQL is always consulted for available quantity; Redis cannot sell tickets | Reservation and cache suites |
| Rate limiting | Redis Lua atomic limits for login, reservations and orders | `RedisRateLimiterIT` |
| Outage/degradation | Public catalog fallback; access JWT local verification; refresh/session state fails closed when Redis unavailable | `RedisOutageIT` |
| Recovery | Cache refill and refresh failure policy after Redis returns | `RedisOutageIT`, caching services |

The original cache-hit criterion must be backed by tests that actually distinguish cache-hit from cache-miss; merely finding a Redis key is insufficient.

## B. H75–H84 evidence matrix

| ID | Topic and established mechanism | Current technical disposition | Governance |
| --- | --- | --- | --- |
| H75 | Global lock protocol, ADR-016, historical CI #292 | Preserve deterministic acquisition and implicit FK locks | Final Tech Lead acceptance pending |
| H76 | Inventory before reservation (T → R) under event locking | Existing concurrency tests, database time after locks | Pending |
| H77 | Commercial units and ticket generations, Flyway V16, `CommercialIssuanceIT` | Implemented/tested | Pending |
| H78 | Operation cycles, jobs, leases, fencing and recovery, Flyway V15 | REV-DEV-01 defect corrected and tested on CI #324; review other possible interleavings and AUD-12 | Pending |
| H79 | Late captures, grace and non-recoverable stock | ADR-019 and order/payment regressions; REV-DEV-02 correction on CI #324 | Pending |
| H80 | Provider event/operation/semantic identity, append-only evidence, digests | V14/V15 and payment flow regressions; new signed event vs same job must remain distinguished | Pending |
| H81 | Redis cache/session/rate outage behavior | ADR-013/014/020 and RedisOutageIT | Pending |
| H82 | Operation timeouts/configuration | OperationPropertiesTest, actual PostgreSQL timeouts in tests | Pending |
| H83 | FK/flush implied lock acquisitions | Historical F4-03 audit and CI #292, no EN → U or R → T lock inversion | Reopen if affected by changes |
| H84 / H84-01 | REFUNDED/PENDING/FAILED event ordering, financial effects and concurrency | Proposal ADR-022, `OrderPaymentFlowIT`; new worker recovery regression prevents stale technical replay | **ADR-022 verdict required** |

**Original individual H75–H84 severity assignments were not recoverable in current source documents.** Do not guess or change their classification.

## C. Proposed ADR-022 and H84-01

[ADR-022](../adr/ADR-022-simulated-refund-evidence-contract.md) is a **PROPOSAL** that the locally simulated provider must retain stable semantic operation identity: provider code, merchant account, namespace and providerOperationId. `providerEventId` identifies each signed notification. The semantic digest includes order, operation kind, provider reference, amount and currency but not the progression state.

- Changing the same operation's reference/digest must return a conflict (HTTP 409); receipt does not silently mutate immutable evidence.
- An early `REFUNDED` notification before a capture can have business disposition `NO_OP`; no real refund is fabricated.
- A **new, independently signed** notification with another providerEventId and consistent semantic identity can later progress to `APPLIED` after the captured payment becomes refundable.
- A retry of the **same job** after APPLY committed must recover the saved decision/projection only; **it must never reevaluate business effects** simply because payment state changed. CI #324 adds that regression.
- There is no automatic financial reconciler, production payment gateway, cash transfer or real provider signature timestamp/replay window.

The proposed contract requires an **explicit independent verdict**, not unilateral acceptance.

## D. Additional open issues and quality controls

- **AUD-05:** Other coordination/query paths cross domain module boundaries. ADR-024's specific event cancellation exception does not approve them by analogy. Document ownership and narrow transaction-compatible exceptions or justified service interfaces.
- **AUD-03:** Review 201 Location semantics per resource without automatically adding unsafe GET APIs.
- **AUD-10:** Missing proven HTTP correlation/MDC implementation; decide minimal HTTP scope before the future async architecture, but do not implement Kafka propagation during Phase 4.
- **AUD-12:** Check whether additional operation cycles are required beyond the initial cycle; do not invent a cycle API from the table.
- **BACK-01/BACK-04:** Name normalization concurrency and JVM/DB expiration discovery are static risks that require a reproducible test before corrections.
- **Language and CI:** The owner now expressly requires English-only active repository content (owner's instruction supersedes the original Spanish prose convention); [ADR-025](../adr/ADR-025-english-only-project-policy.md) records policy. Ensure final CI includes Java, Angular and Python, plus permanent regressions.

## E. Historical versus current evidence

- Historic `dev` CI #292 established lock ordering; #306 checked operation fencing; #309 tested signed refund progression; #315 checked event-cancellation rollback; #320 confirmed ERD consistency.
- Modern `main` CI #322 established 407 unit and 179 integration tests, with Spotless and JaCoCo green.
- Modern `main` CI #324 established 407 unit and 181 integration tests, including REV-DEV-01/02 regressions.
- Modern `main` CI #325 verifies input/log hardening.
- Any additional frontend, documentation or source changes require **a new successful CI on that final SHA** before acceptance.
- `docs/LOCAL_RUN.md` contains earlier local smoke evidence; do not claim that a new complete browser E2E test ran unless its actual observations are included.

## F. Formal closure gate

1. Phase 3 must already be **formally closed**, not merely green.
2. Original Phase 4 Redis/cache/rate/outage acceptance fulfilled and recorded.
3. Every H75–H84 disposition reviewed with source, tests, SHA and Tech Lead conclusion.
4. ADR-022/H84-01 contract expressly accepted or replaced by an approved alternative.
5. All blocking business-integrity, privacy and security regressions resolved, and new debt assigned an authorized disposition.
6. Final English documentation, correct branch description, verified CI across Java/Angular/Python for the final SHA.
7. **Independent Tech Lead technical verdict in issue #6**, followed by **owner's formal Phase 4 closure**.

**Current status:** Code coverage of Phase 4 scenarios is strong, but architectural and contractual approvals remain open. This dossier is a candidate for review, **not proof of formal closure**. Phase 5 Kafka/outbox/Mongo notification functionality remains BLOCKED.

## G. Prepared Tech Lead decision request — not sent

> Please review the final main SHA and updated Phase 4 dossier after formal Phase 3 closure. Approve/reject ADR-022 for the SIMULATED provider with particular attention to early-refund NO_OP, a distinct signed event and technical PROJECT recovery. Confirm the H75–H84 evidence matrix, original Redis acceptance criteria, residual finding dispositions and whether Phase 4 can be formally closed. No approval or issue-state change is presumed.

## F4 technical follow-up — 2026-11-22 (developer evidence; not an approval)

The durable same-job APPLY/PROJECT recovery regression and distinct signed event behavior stay covered in `OperationWorkerIT` and `OrderPaymentFlowIT`, including previously corrected REV-DEV-01/02 and PostgreSQL/Redis scenarios. A later Angular session-context correction additionally prevents a delayed response from a prior account being displayed or triggering navigation under the next account. New headless Chrome direct/reload deep-route verification is included in the Angular CI workflow; its scope is browser SPA bootstrap and asset loading, not a paid-order E2E flow.

Historic H75–H84 severities are not recreated. ADR-022/H84-01 remains **PROPOSED**, not accepted, and cannot be approved before the Phase 3 formal closure and independent Phase 4 review. AUD-05/ADR-024 has no blanket exception. GitHub [issue #6](https://github.com/dab960405/tickeringsystem/issues/6) remains open; no comments or phase-state changes were made. Use the final SHA/CI in the [engineering verification dossier](../audit/READINESS-VERIFICATION-2026-11-22.md).


**Exact post-remediation code verification:** [CI #348](https://github.com/dab960405/tickeringsystem/actions/runs/44986963218), `main@e318c46f0623ee2cdebaf2dbe8241b8b7ddf78d7`, completed **SUCCESS**: 410 Java unit tests, 195 PostgreSQL/Redis Testcontainers integrations (including 13/13 ticket-type name races), Maven `clean verify`, Spotless and JaCoCo; Angular 19/19 with a production build, Prettier and **real Google Chrome** direct/deep-route independent reload and legacy bookmark with JS/CSS HTTP 200; Python frontend 11/11 and scripts 3/3. This is GitHub Actions evidence, **not** a fresh local Docker or full paid-order browser E2E run. Any later documentation commit requires separate same-SHA CI verification; neither test success nor this report grants Tech Lead acceptance.

## F3 owner-decision dependency update — 2026-11-22

The owner has authorized Phase 3 decisions for the bounded [ADR-024](../adr/ADR-024-event-cancellation-transactional-exception.md) and [ADR-026](../adr/ADR-026-bounded-phase-3-cross-module-coordination.md), plus the documented AUD-03/AUD-10/AUD-12 dispositions in [F3-02](../phase-3/F3-02-owner-disposition-2026-11-22.md). These decisions **do not constitute the independent Phase 3 technical verdict**. Phase 4 retains its original H78/H84-01 business requirements, ADR-022 approval requirement, issue #6 and its separate acceptance and closure gates. F4 remains **OPEN**.
