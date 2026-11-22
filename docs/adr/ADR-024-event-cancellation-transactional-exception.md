# ADR-024 — Proposed narrow transactional exception for event cancellation

- **Status:** PROPOSED for independent Tech Lead verdict; publication is **not** acceptance.
- **Date:** 2026-11-14
- **Phase:** 3
- **Related:** [ADR-016](ADR-016-global-lock-order.md), [F3-01](../phase-3/F3-01-closure-review.md).
- **Scope:** Document/review an existing exception. This ADR does **not** independently authorize broad refactors, migrations or public API changes.

## Context

`EventCancellationService` coordinates `events`, `ticket_types`, `reservations` and `orders` inside **one PostgreSQL transaction**. It calls `EventRepository`, `TicketTypeInventoryService`, `ReservationRepository`, `OrderRepository`, `ReservationRedisProjectionService` and cross-module `JdbcTemplate` SQL. These direct dependencies violate the default module-boundary rule of communicating through services/events, although the current code follows the required lock order from ADR-016.

The flow acquires E FOR UPDATE → sorted T → sorted R FOR UPDATE → sorted O FOR UPDATE. Cancelling an event is **not** financial evidence of payment failure or refund. Redis reservation projections are removed **after commit**, never as commercial authority.

## Options and trade-offs

| Option | Benefit | Cost/risk | Assessment |
| --- | --- | --- | --- |
| Keep cross-module access silently | Zero changes | Hides architecture violation | Rejected |
| **Accept a narrow exception for this coordinator** | Preserves one transaction, locks and successful regressions; simpler for portfolio | Explicit coupling across modules | **Recommended for approval** |
| Refactor to other modules' application-service interfaces | Improves boundary isolation | May reopen atomicity, lock ordering and races | Only if required after Tech Lead review |
| Saga/asynchronous compensations | Decoupling | Changes business semantics, distributed consistency and infrastructure | Outside Phase 3 |

## Precise proposed conditions

1. Only `EventCancellationService.cancel(UUID eventId)` may directly coordinate reservations/orders/inventory repositories/SQL **for event cancellation**. No general exception is implied for other callers or coordination paths.
2. Preserve one PostgreSQL transaction and E → T → R → O lock acquisition including implicit FK validation. Do not introduce inversions (ADR-016).
3. On any step failing, roll back the **entire** event/reservation/order/inventory transition; Redis must not delete projections on rollback. Keep Spring `@Transactional` and post-commit callback.
4. Only terminalize eligible HELD reservations and PENDING_PAYMENT orders, without double stock release or invented payment/refund transitions.
5. Recheck states/relationships **after locks**, maintain idempotent repeated cancellation and preserve confirmed purchases.
6. Reopen ADR-024 and ADR-016 if new entities, foreign keys, side effects or cancellation entry points are introduced.
7. Record the exception as bounded technical debt; do **not** apply it to unrelated module accesses by analogy.

**AUD-05 broader review:** `OrderExpirationProcessor`, `ProviderEventInboxService`, `PaymentOperationService`, `OperationJobService` and repository discovery queries also use foreign persistence. They require **separate ownership and transaction-boundary disposition**. This proposal alone cannot approve them.

## Execution evidence

- Source: `ticketing-api/src/main/java/com/portfolio/ticketing/event/service/EventCancellationService.java`.
- `EventGuardConcurrencyIT` originally had nine tests on CI #312 covering idempotent cancellation, captures/late-payment races and protection of confirmed orders.
- New `should_rollbackEventReservationAndOrder_when_stockRestorationFails` uses a PostgreSQL trigger to abort stock restoration and checks event/reservation/order/inventory rollback plus Redis projection integrity.
- Historical progression: added at `4d381899`, SQL fixture corrected at `c18d9f2`, initial stock-baseline assertion corrected at `9d11d833`. CI #313 failed SQL syntax, #314 failed fixture expectation. [CI #315](https://github.com/dab960405/tickeringsystem/actions/runs/43654824223) SUCCESS on `9d11d83344b88007e2b5d89de698bf62899817c0`: 390 unit, 171 integration, EventGuardConcurrencyIT 10/10, Spotless and JaCoCo.
- Current `main` CI #324 independently exercises EventGuardConcurrencyIT 10/10 within 407+181 tests.
- Successful CI is technical evidence, **not independent architecture approval**.

## Double-check and conditions

[Source-verification matrix](../engineering-decision-verification.md) compared historical source SHA `d92238a9...`/CI #318 with Spring Framework 6.2 (proxy-declared transactions, rollback and afterCommit), PostgreSQL 17 (locks/deadlocks) and Spring Modulith 1.4 (module-boundary verification). This validates the mechanisms, not a universal right to break module boundaries.

Preserve these constraints when assessing any refactor:
- `@Transactional` requires calls through the Spring transactional proxy; self-invocation does not activate new advice.
- Checked exceptions do not roll back by default unless configured.
- Redis projection deletion is delayed **only with active transaction synchronization**; otherwise the helper executes immediately.
- The rollback integration proves a specific failure scenario, **not all possible concurrent interleavings**.

## References and pending decision

- [Spring Framework — REQUIRED propagation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html).
- [Spring Framework — declarative rollback](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html).
- [PostgreSQL 17 — explicit locking and deadlocks](https://www.postgresql.org/docs/17/explicit-locking.html).
- [Spring Modulith — module verification](https://docs.spring.io/spring-modulith/reference/verification.html).

**Tech Lead issue:** [#5 — explicit ADR-024 verdict and Phase 3 closure](https://github.com/dab960405/tickeringsystem/issues/5). Its acceptance and the project owner's formal phase close remain **PENDING**. The exception is specific to Ticketing and is not a Spring/PostgreSQL vendor recommendation.
