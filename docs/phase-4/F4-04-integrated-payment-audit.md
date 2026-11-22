# F4-04 — Integrated operations and simulated-refund audit

- **Date:** 2026-11-14.
- **Scope:** H78, H79, H80, H82 and H84; ADR-018/019 and F4-01/F4-02.
- **Historical evidence baseline:** `88a8f717d4c3079d124166dff487167e373fec6d`, CI #295; later expanded validation `b8f1037b9a6f923b70ab92ef0071423c0479b1c0`, CI #306, following CI #302 financial cases and CI #304 fencing checks.
- **Historical status:** Disposition classification, `FAILED → REFUNDED` and `PENDING/REFUNDED` races passed CI #302. Recovery after APPLY, stale-projection fencing and interrupted `PENDING → REFUNDED` with a stable reference passed CI #304/#306. **Provider-reference evolution, early refund reconciliation contract, H84 and formal Phase 4 closure were still pending.**
- **Later finding and correction:** The original tests did not fully exercise interrupted APPLY/PROJECT where an early `NO_OP` refund is recovered after a separate successful capture. REV-DEV-01 later reproduced erroneous same-job reapplication; current main implemented a durable outcome regression/fix. Historical CI must not be presented as proof that this later bug was already absent.

## Historic CI evidence

[CI #295](https://github.com/dab960405/tickeringsystem/actions/runs/43634870599) was successful:

| Suite or gate | Recorded result |
| --- | --- |
| `ticketing-api` Surefire | 390; zero failures/errors/skips |
| `ticketing-api` Failsafe | 152; zero failures/errors/skips |
| `OrderPaymentFlowIT` | 20/20 |
| `OperationWorkerIT` | 10/10 |
| `OperationPropertiesTest` | 16/16 |
| Spotless | 220 clean API Java files |
| JaCoCo | All configured checks met |
| Maven | `BUILD SUCCESS` |

Four tests introduced after CI #293 covered a `FAILED` refund without financial confirmation/ticket cancellation, `PENDING_RECONCILIATION` without false refund confirmation, a premature `REFUNDED` before capture, and worker projection recovery without intended duplicate effects on a new claim. Concurrency/reservation/ticket/expiration suites also ran in `verify`.

H82 validation in `OperationPropertiesTest` covered limits/defaults. `OperationTransactionExecutor` uses PostgreSQL `SET LOCAL` for `lock_timeout` and `statement_timeout`. Commit `522a2dcf706122760ffee4fd46e8027e41e2723d` added an actual PostgreSQL query test inside RX/CLAIM/APPLY/PROJECT. [CI #296](https://github.com/dab960405/tickeringsystem/actions/runs/43635133428) succeeded with 390 units, 153 integrations, `OperationWorkerIT` 11/11, JaCoCo/Spotless.

## CI #298 — Correctly classify actual business effects

Commit `4acb8240e951cebc2ac3178d3543db1a74549d23` separated `PaymentEffectOutcome` from the result of inserting a semantic identity row into `semantic_applications`. `PaymentOperationService` now records `APPLIED` or `NO_OP` and JSON disposition according to the **actual commercial effect**, while `OrderPaymentService` retains its public `process` method and adds `processWithOutcome`. No migration or global lock order change was made.

[CI #298](https://github.com/dab960405/tickeringsystem/actions/runs/43640665242) completed `success`: 390 units, 157 integrations, `OrderPaymentFlowIT` 24/24, `OperationWorkerIT` 11/11, 221 clean API Java files, JaCoCo and `BUILD SUCCESS`.

The four new cases covered:
1. `PENDING → REFUNDED` for the same provider operation and financial reference.
2. Premature refund with **no** business effect.
3. Repeated `REFUNDED` via another provider event.
4. `REFUNDED → PENDING` not regressing an already confirmed refund.

Disposition matched the actual business change; each provider operation retained one `semantic_applications` identity row.

## CI #302 — Extended refund transitions and concurrency

Added `should_confirmRefundAfterFailedEvidenceForSameProviderOperation` and `should_notLoseRefund_whenPendingAndRefundedArriveConcurrently` with `@RepeatedTest(10)`. Each test reused provider operation ID and financial reference across states, with distinct capture/refund references among fixtures. The concurrent test asserted eventual `REFUNDED`, an `APPLIED` final event, `APPLIED` or `NO_OP` for `PENDING` depending on interleaving, a single semantic identity and cancelled tickets.

Historical sequence:
- CI #300 failed because the same fixed provider reference collided with `uq_payments_provider_reference` across repetitions.
- CI #301 ran **390 unit and 168 integration tests successfully**, but failed only Spotless formatting.
- Commit `30517047d80e7b861874b0a81284fe921ba1e068` applied the exact format diff.
- [CI #302](https://github.com/dab960405/tickeringsystem/actions/runs/43642005154) **SUCCESS** on that SHA: 390 unit, 168 integration, `OrderPaymentFlowIT` 35/35, `OperationWorkerIT` 11/11, 221 clean Java, JaCoCo, `BUILD SUCCESS`; no tests were removed or weakened.

## CI #304 — Refund projection recovery and stale fencing

Commit `f12cc6c5ce9d48772c54dd729597d5f2ff51ce36` extended `OperationWorkerIT.should_recoverRefundProjectionWithoutRepeatingFinancialEffects`. It added an assertion that a stale interrupted worker projecting after another worker's successful recovery receives `OperationFencingException`.

Earlier assertions checked correct order/payment/refund state, exactly-once ticket cancellation, one semantic identity, two worker executions, `APPLIED`/`NO_OP` dispositions and a `COMPLETED` projection. The scenario started from a direct `REFUNDED` provider event; **it did not prove interruption of a state progression `PENDING → REFUNDED` within the same operation**.

[CI #304](https://github.com/dab960405/tickeringsystem/actions/runs/43642792880) **SUCCESS**: 390 units, 168 integrations, `OrderPaymentFlowIT` 35/35, `OperationWorkerIT` 11/11, Spotless 221, JaCoCo and `BUILD SUCCESS`. Prior [CI #303](https://github.com/dab960405/tickeringsystem/actions/runs/43642435399) also passed for the earlier document-only commit.

## CI #306 — Interrupted PENDING → REFUNDED transition

Commit `b8f1037b9a6f923b70ab92ef0071423c0479b1c0` introduced `OperationWorkerIT.should_recoverInterruptedPendingToRefundedTransitionAndFenceStaleProjection`.

After a paid purchase requested refund, `PENDING` and later `REFUNDED` arrived for the **same provider operation**, reference, amount and currency but distinct `providerEventId`. The test deliberately interrupted execution after APPLY but before PROJECT, recovered with another worker and expected `OperationFencingException` from the original worker's stale projection attempt.

Assertions covered confirmed refund, cancelled ticket, one semantic identity row, two executions, `APPLIED`/`NO_OP` dispositions, a `COMPLETED` projection and projected financial status `REFUNDED`. This was **controlled transaction interruption**, not an actual external process crash.

[CI #306](https://github.com/dab960405/tickeringsystem/actions/runs/43644409880) **SUCCESS**: 390 units, 169 integrations, `OrderPaymentFlowIT` 35/35, `OperationWorkerIT` 12/12, Spotless/JaCoCo and `BUILD SUCCESS`. [CI #305](https://github.com/dab960405/tickeringsystem/actions/runs/43644135889) had already passed the preceding documentation commit `6c5bdacb14bf7fb22804fe3a4e00593f6d4fc6a7`.

## H84-01 — Semantic identity versus evolving refund status

**Historical status:** The original effect-classification bug and `PENDING/REFUNDED` race passed CI #302; provider reference changes and financial reconciliation were still contractual decisions. CI #306 covered interrupted progression with a stable reference. The original H84 severity is preserved (not unilaterally reclassified).

### State progression verified in CI #298

1. A paid order requests a refund: `refund_status=REQUESTED`.
2. The simulated provider sends `PAYMENT_REFUND` with `status=PENDING`, `providerOperationId=R`, `providerEventId=E1`, and valid reference/amount/currency. Order becomes `PENDING_RECONCILIATION`; provider operation R is present in `semantic_applications`.
3. Another signed provider event E2 arrives for **the same operation R**, `status=REFUNDED` and otherwise unchanged order/reference/amount/currency.
4. E2 confirms the business/financial change with `APPLIED` disposition **despite the one shared semantic identity**. This was verified using a stable reference.

### Original bug diagnosis — SHA `23893305`

- `PaymentEvidenceDigest.semantic` included `orderId`, `operationKind`, `providerReference`, amount and currency, **not `status`**; E1 and E2 therefore shared a semantic digest.
- `ProviderEventInboxService.registerOperation` accepted existing operation R if digest matched; `registerEvent` accepted new E2 because `providerEventId` differed.
- `OperationCycleService.register` created a new participation/job for E2.
- `SemanticApplicationService.apply` evaluated `application.run()` **before** `INSERT ... ON CONFLICT DO NOTHING` on `semantic_applications`. A conflict for existing R returned a semantic `false`/`NO_OP` **after the commercial callback had executed**.
- `OrderPaymentService.processRefund` could confirm refund, mark payment `REFUNDED` and cancel tickets for a successful capture even though the earlier E1 had registered R with `PENDING`.

The pre-fix tests exposed `NO_OP` accompanying a real financial change, or `APPLIED` when a premature refund had no effect. The history above describes the **old code**, not current main.

### Implemented semantics and unresolved contracts

- **Implemented:** A provider operation can publish successive states without creating a second provider-operation identity. Each execution's disposition describes its **actual business effect under locks**; unique `semantic_applications` identity/digest remains append-only.
- **Preserved:** `UNIQUE(provider_operation_id)`, `idempotency_key VARCHAR(100)`, append-only trigger and global ADR-016 lock order. No new status column or lock was introduced.
- **Unresolved reference evolution:** An initial `PENDING` with `providerReference=null` followed by `REFUNDED` with a new reference currently conflicts because digest changes, yielding HTTP 409. No silent enrichment of immutable evidence was approved.
- **Unresolved early refund:** Decide whether a `REFUNDED` before capture is forever `NO_OP` unless a new signed event arrives, or should trigger automatic re-evaluation after capture. Do not invent financial reconciliation.
- **CI #302:** `FAILED → REFUNDED` with stable identity and ten `PENDING/REFUNDED` races, no duplicate business effect.
- **CI #304:** direct-refund projection recovery with stale-worker fencing and no duplicate effect.
- **CI #306:** controlled APPLY/PROJECT interruption during `PENDING → REFUNDED` with semantic uniqueness, final projection and fencing.
- **Later critical qualification:** Subsequent adversarial REV-DEV-01 found that **recovering the same old early-refund job after a later successful capture could re-run commercial application**. A distinct new provider event must be separated from recovery of the same committed APPLY. The subsequent main correction added a permanent regression; consult [the updated remediation dossier](../audit/MAIN-REMEDIATION-2026-11-21.md) and its final CI evidence.

### Contract decisions requested of the Tech Lead — not automatically approved

1. **Changing reference:** May `providerReference=null → non-null` be accepted as constrained enrichment for **the same operation**, or is existing HTTP 409 the definitive contract? Protect against unapproved different references and preserve evidence immutability.
2. **Early refund:** Should `REFUNDED` received before capture remain `NO_OP` until a **new signed event** (minimal simulated-provider proposal), or should it be automatically reconciled upon later capture? Specify authorization/ordering without fictitious refunds/double effects.
3. **Recovery and fencing:** Controlled interruption between APPLY and PROJECT during a stable-reference `PENDING → REFUNDED` passed CI #306. This does not replace decisions 1/2; the later same-job replay regression also requires validation.

**Portfolio recommendation:** [ADR-022](../adr/ADR-022-simulated-refund-evidence-contract.md) proposes retaining HTTP 409 for reference changes and accepting **a new HMAC-signed notification** with stable semantic identity after a capture. `OrderPaymentFlowIT.should_applyPreviouslyEarlyRefund_whenNewSignedEventArrivesAfterCapture` was added in `8e2c8787a7fe5bb3c8c8d1571bf523295e413154`; CI #308 passed tests but failed Spotless. The format commit `a8013f67bffe3a5826606469485335b61e13a93d` passed [CI #309](https://github.com/dab960405/tickeringsystem/actions/runs/43647059382): 390 units, 170 integrations, `OrderPaymentFlowIT` 36/36, `OperationWorkerIT` 12/12, JaCoCo/Spotless, `BUILD SUCCESS`.

**ADR-022 remains PROPOSED, not Tech Lead-approved.** Documentation publication does not close H84/Phase 4.

## Historical technical disposition

- **H78:** Workers, fencing and recovery tested for covered scenarios; no formal acceptance or Phase 4 closure. Later REV-DEV-01 specifically strengthened same-job recovery.
- **H79:** Late capture, recovery grace, inventory and `UNFULFILLABLE` behavior tested; accepted ADR-019 governs the policy.
- **H80:** Persisted identities, append-only constraints and semantic idempotency tested. H84-01's dispositions now report actual effects while preserving one operation identity; mutable reference policy remains pending.
- **H82:** Worker configuration/transaction timeout values tested with PostgreSQL in CI #296; pending integration dossier acceptance is not a demonstrated H82 code defect.
- **H84:** Original classification and `PENDING/REFUNDED` race tested in CI #298/#302; stale recovery fencing in #304/#306; new signed event after capture in #309. **Contract verdict still required.**

The cross-module `EventCancellationService` exception remained proposed for Tech Lead review to close Phase 3; see ADR-024 and [F3-01](../phase-3/F3-01-closure-review.md).

**This document neither approves architecture, closes Phase 4 nor authorizes Phase 5.** Consolidated reviewer dossier: [F4-05](F4-05-closure-review.md).
