# ADR-022 — Minimum refund evidence contract for the simulated provider

- **Status:** PROPOSED for independent Tech Lead verdict — NOT ACCEPTED.
- **Date:** 2026-11-14
- **Phase:** 4, H84-01.
- **Relation:** Proposed clarification of accepted ADR-019 without altering its approved guarantees.
- **Nature:** Portfolio system and local financial simulation. **No real payment provider integration.**

## Context and objective

The specification explicitly requires simulated payments, clear code, simple architecture and reproducible tests. The system already persists three identities, immutable digests, append-only evidence, dispositions derived from actual commercial effects and fencing. A new automatic financial reconciler or persistent subsystem is not required to demonstrate the current simulated flow.

## Alternatives

| Topic | Conservative option | Expanded option | Recommendation |
| --- | --- | --- | --- |
| Same operation changes `providerReference` | Immutable digest; HTTP 409 | Enrich `null → non-null` with extra persistence and concurrency rules | **Keep immutable digest** |
| `REFUNDED` arrives before capture | Record NO_OP and require a later, newly signed event | Automatic reconciliation without new evidence | **New signed event, no reconciler** |

## Proposed contract (requires explicit acceptance)

1. Stable operation identity is `(provider_code, merchant_account_id, identity_namespace, providerOperationId)`. `providerEventId` uniquely identifies each signed notification. The semantic digest includes order, operation kind, provider reference, amount and currency **but not state**.
2. When a refund is simulated as one operation progressing through states PENDING, FAILED and REFUNDED, the **first notification must include a non-null stable financial reference**. Later events preserve reference/order/kind/amount/currency; only state and `providerEventId` change.
3. The current API permits some PENDING/FAILED evidence with null reference. Those events are accepted and stored, but **cannot later add a reference within the same operation**: digest changes and HTTP 409 is returned. The portfolio contract deliberately preserves that limitation.
4. An early REFUNDED notification before capture produces immutable evidence and **NO_OP**, without implying a completed refund. The subsequent capture is processed normally; it does not itself reconcile the early refund.
5. To demonstrate manual simulated reconciliation, submit a **different valid HMAC-signed event** with a new `providerEventId`, identical provider operation identity and compatible semantic digest. After capture, the current commercial state is revalidated. A real new state change yields APPLIED; duplicate/no-effect events yield NO_OP.
6. Preserve UNIQUE constraints, append-only triggers, HMAC, ADR-016 global lock order, operation timeouts and fencing. Do not mutate earlier evidence or automate replay without an explicit cause.

**Critical technical distinction clarified by REV-DEV-01:** a retry of the **same already-applied job** after an APPLY/PROJECT interruption is not a new provider event and must **not reevaluate business**. A committed job disposition can be projected without invoking its commercial callback. The corrected implementation and its regression were validated on [CI #324](https://github.com/dab960405/tickeringsystem/actions/runs/44981366848). This does not approve ADR-022.

## Consequences

- No public API changes, new tables, V14/V15 edits, dependencies, jobs or lock protocols.
- A null-reference initial event cannot later obtain a reference while keeping its providerOperationId; document this rather than conceal it.
- Simulated reconciliation requires another independently signed notification **after capture**. It is **not** automatic financial reconciliation.
- If scope changes toward a real payment provider, reopen this decision and approve a separate ADR before adding reference enrichment or persistent reconciliation.

## Validation and approval

- Historical CI #302 covered PENDING/REFUNDED races and dispositions; #306 covered state progression and fencing.
- `OrderPaymentFlowIT.should_applyPreviouslyEarlyRefund_whenNewSignedEventArrivesAfterCapture`, introduced at `8e2c8787...` and formatted in `a8013f67...`, passed [CI #309](https://github.com/dab960405/tickeringsystem/actions/runs/43647059382), with 390 unit, 170 integration, `OrderPaymentFlowIT` 36/36 and `OperationWorkerIT` 12/12.
- The separate **same-job** recovery regression added later in CI #324 passed alongside 181 total integration tests.
- Tech Lead must explicitly ACCEPT/REJECT this **minimum SIMULATED contract** and the H84-01 disposition. Documentation or successful CI does **not** close Phase 4.

## Two-source mechanism cross-check

[Verification matrix](../engineering-decision-verification.md) compared PostgreSQL 17 uniqueness and ON CONFLICT, Java 21 HMAC-SHA256/constant-time comparison, and RFC 9110 §15.5.10 HTTP 409 against the code on historical `d92238a9...`/CI #318. This supports **mechanisms**, not the simulated business contract itself.

The digest includes providerReference but not status; PENDING/FAILED with null reference followed by the **same operation** with a new reference returns 409. REFUNDED cannot be reevaluated from an old applied job without a **new** signed provider event. There is no automatic reconciler or timestamp-based anti-replay guarantee.

## References

- Original Ticketing specification §§1,2,10,15,22,24 (functional source).
- [PostgreSQL 17 — UNIQUE constraints](https://www.postgresql.org/docs/17/ddl-constraints.html) and [INSERT ON CONFLICT](https://www.postgresql.org/docs/17/sql-insert.html).
- [Spring Framework — declarative rollback](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html).
- [IETF RFC 9110 — 409 Conflict](https://www.rfc-editor.org/rfc/rfc9110.html#section-15.5.10).

The decision of **how a simulated refund progresses** belongs to Ticketing governance, not to PostgreSQL or Spring vendors.
