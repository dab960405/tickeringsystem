# ADR-026 — Bounded cross-module coordination in Phase 3

- **Status:** OWNER-APPROVED on 2026-11-22; independent Tech Lead conformity verdict **PENDING**.
- **Scope:** Existing Phase 3 event cancellation, overdue-order processing and read-only expiration candidate discovery. No general permission to import other modules' persistence internals.
- **Related:** [ADR-016](ADR-016-global-lock-order.md), [ADR-024](ADR-024-event-cancellation-transactional-exception.md), [F3-01](../phase-3/F3-01-closure-review.md), [AUD-05 register](../audit/PENDING-DECISIONS.md).

## Context and root cause

The modular monolith owns one PostgreSQL business transaction for operations that must change inventory, reservations and orders atomically. Some existing coordinators access foreign module repositories or perform joins across module-owned tables. Spring Modulith recommends API-level module interaction; the accesses are deliberate architectural exceptions, not evidence of clean module isolation. Refactoring without tests could change transaction propagation, implicit foreign-key locks or revalidation.

## Decision authorized by the project owner

1. **Event cancellation:** Approve the existing exception only for `EventCancellationService.cancel(UUID)` under ADR-024. Preserve one physical PostgreSQL transaction, E → T → R → O locking, revalidation, rollback of the entire transition, idempotency, no fabricated financial evidence, and Redis deletion after commit.
2. **Overdue orders:** Authorize direct `ReservationRepository` use only within `OrderExpirationProcessor.expire(OrderExpirationCandidate)`. That method must retain the existing E → T → R → O sequence and a single physical transaction. A previously RELEASED/CANCELLED reservation must never release inventory again. Do not infer provider payment failure from order expiry.
3. **Read-only discovery:** Allow `OrderRepository.findExpiredPendingOrderCandidates` and `ReservationRepository.findExpiredCandidates` to join foreign tables solely for bounded, unlocked candidate discovery. They must not substitute for authoritative state revalidation **after** the relevant row locks.
4. **No blanket exception:** Foreign repository **writes**, new coordinators, changed transaction propagation, additional FK relationships, payment-evidence changes, new callers, or lock-order changes require a new review and regression evidence.
5. **Separate Phase 4:** `ProviderEventInboxService`, `PaymentOperationService`, `OperationJobService` and the broader H75–H84 flow remain under the Phase 4 conformity review. This ADR does not approve their independent contracts or close Phase 4.

## Alternatives considered

- **Implicit cross-module access:** Rejected; hides coupling and makes new violations hard to identify.
- **Module-owned application interfaces:** Preferred for future maintenance where safe, but changing the current synchronous transaction without verified equivalence risks inventory/payment regressions; not necessary to resolve demonstrated Phase 3 correctness defects.
- **Saga/eventual consistency:** Rejected for this Phase 3 transition because it would alter the accepted atomic business contract and require new infrastructure.

## Consequences and verification

The trade-off is bounded, documented coupling and potential row-lock contention in exchange for retained PostgreSQL atomicity. Revisit during modular hardening; do not add application-level locking or Kafka to replace database transaction semantics. Preserve the existing PostgreSQL Testcontainers rollback, concurrent event cancellation and order-expiration tests, in addition to Maven Spotless and JaCoCo.

- Repository: `EventCancellationService`, `OrderExpirationProcessor`, `OrderRepository`, `ReservationRepository`, `ReservationExpirationService`; approved lock protocol in ADR-016.
- Verified pre-decision baseline: [CI #349](https://github.com/dab960405/tickeringsystem/actions/runs/44987202435), `main@9d843c6d0bd78f8bf1fd5d56231c8ed96ba66917`, 410 Java unit + 195 PostgreSQL/Redis integrations, all jobs successful.
- [Spring Framework 6.2: declarative transactions](https://docs.spring.io/spring/reference/6.2/data-access/transaction/declarative/annotations.html), [rollback](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/rolling-back.html).
- [PostgreSQL 17: explicit locks and deadlock avoidance](https://www.postgresql.org/docs/17/explicit-locking.html).
- [Spring Modulith: verify module structure](https://docs.spring.io/spring-modulith/reference/verification.html).

The cited external documents justify mechanisms and trade-offs; **the exception is a project-specific owner decision**, not a recommendation imposed by Spring or PostgreSQL. The required independent Tech Lead review remains open and must not be impersonated by the developer or treated as CI success.
