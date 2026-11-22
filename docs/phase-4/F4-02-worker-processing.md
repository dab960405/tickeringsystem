# F4-02 — Recoverable operation processing

- **Date:** 2026-11-13.
- **Scope:** H78, H80 and H82; ADR-016, ADR-018 and ADR-019.
- **Historical status:** Implemented and tested with real PostgreSQL/Testcontainers. **Phase 4 still formally open.**

## Persisted model

Flyway `V15__operation_cycles_jobs_and_projections.sql` creates:

- `operation_cycles`: numbered cycles per aggregate.
- `operation_participations`: unambiguous association of cycle and received provider event.
- `operation_jobs`: generation, effective flag, technical status, attempt count and `fencing_token`.
- `operation_executions`: worker ID, token, lease and execution status.
- `operation_dispositions`: append-only `APPLIED`, `NO_OP` or `FAILED` result per execution.
- `operation_projections`: separate technical, commercial and financial statuses with version/token.

V14 retains separate provider event, operation and semantic application identities. **Important refinement:** global semantic operation identity does not mean a legitimate *new signed provider event* can never change business state; proposed ADR-022 specifies valid progression. It does prevent reapplying an already committed event/job due merely to a technical retry.

## Transaction boundaries

`OperationTransactionExecutor` creates independent transactions using validated `OperationProperties` values:

| Transaction | Implementation | Invariant |
| --- | --- | --- |
| TX-RX | `ProviderEventInboxService` + `OperationCycleService` | Persists identities, received event, participation, job and initial projection; no commercial side effect |
| TX-CLAIM | `OperationJobService.claim` | Locks J before writing XA, invalidates expired leases and produces new fencing token |
| TX-APPLY | `PaymentOperationService.apply` | Rechecks J → XA, token and lease with PostgreSQL `clock_timestamp()`; persists semantic identity and business effect in the same TX |
| TX-PROJECT | `PaymentOperationService.project` | Reads committed evidence and updates K; **must never invoke an already committed commercial callback again** |

Each transaction configures PostgreSQL `lock_timeout` and `statement_timeout` through SET LOCAL/`set_config`. Upper time limits come from `tx-rx-timeout`, `tx-claim-timeout`, `tx-apply-timeout` and `tx-project-timeout`.

## Leases, fencing and recovery

- Two workers cannot claim one job while an effective lease is active.
- `renewLease` can renew only a current execution with its valid fencing token.
- Expiration allows a new claim with a greater token; the stale worker must not apply or project.
- `replace` marks the former job `REPLACED` and ineffective, invalidates its executions and creates a greater job generation.
- A failed APPLY rolls back its semantic business change and records failure disposition; job returns to `PENDING` with backoff or becomes `FAILED` after attempt exhaustion.
- **Original design requirement:** If a process stops after a committed APPLY, recovery only resumes the unfinished projection; it reuses the **durable original outcome**, including `NO_OP`. The old implementation incorrectly invoked APPLY again and later failed the adversarial REV-DEV-01. A subsequent main-branch correction adds a permanent regression for that exact interruption. Do not describe the old behavior as valid idempotence.
- `OperationRecoveryWorker` processes bounded batches at `recovery-interval`. The test profile disables its scheduler so another Spring test context cannot steal jobs from shared containers. `OperationRecoveryWorkerTest` checks batching and continuation after fencing; integration tests explicitly control recovery. Production defaults to `recovery-enabled=true`.

## Historical test evidence

The original baseline of this document included **9** `OperationWorkerIT` cases on PostgreSQL:

1. Competing workers claiming the same job.
2. Lease renewal.
3. Job generation replacement and prior execution fencing.
4. Expired lease and stale-worker rejection.
5. Event lock before J/XA during APPLY.
6. Projection recovery without intended business reapplication.
7. Retry exhaustion with no lasting semantic application.
8. Another operation cycle without duplicate global semantic effects.
9. Concurrent duplicate webhook with one ticket issuance.

The same historical `OrderPaymentFlowIT` report contained **17** end-to-end cases for purchase/failure, late capture, concurrent expiration, refunds, persisted identity, operation identity races and append-only evidence.

### Later reference count

The 2026-11-21 audit recorded `OperationWorkerIT` **12/12** and `OrderPaymentFlowIT` **36/36** at [CI #320](https://github.com/dab960405/tickeringsystem/actions/runs/43670018463), SHA `76a0fbb2380d009e13df75e1ab7e9a80f14b69e7`. Counts of 9 and 17 above are historical. A local run is separately recorded in the [audit dossier](../audit/AUD-2026-11-21.md). Later REV-DEV-01 main regression changed recovery behavior and needs its own matching CI evidence.

## Remaining governance

H77 and H81 were implemented and technically verified. [F4-03](F4-03-lock-audit.md) recorded H75/H76/H83 lock checks at CI #292 after two implicit-lock inversions were corrected. H78/H79/H80/H82/H84 still require independent final technical acceptance under [F4-05](F4-05-closure-review.md). This document does **not** formally close Phase 4 or authorize Kafka/Phase 5.
