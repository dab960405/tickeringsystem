# ADR-018 — Operation cycles, leases, fencing and projections

- **Status:** ACCEPTED
- **Date:** 2026-11-13
- **Phase:** 4
- **Addresses:** H78

## Context

Receipt of a signed provider webhook, executing its technical job and applying a commercial operation are distinct events. Timeout does not prove a preceding transaction failed to commit. A worker whose lease expired must not continue writing. A technically complete job also does **not** prove that a payment/refund financially completed.

## Persistent model

- `operation_cycles` (**CR**): numbered cycle of a business aggregate.
- `operation_participations` (**C**): provider event participating in one cycle, with unambiguous event and cycle FKs.
- `operation_jobs` (**J**): one participation's job, with generation, `effective`, `fencing_token`, technical state and attempt count. Partial unique index permits at most one effective job per participation.
- `operation_executions` (**XA**): each worker claim with worker ID, token, lease expiry and state `CLAIMED`, `APPLIED`, `PROJECTED`, `COMPLETED`, `FAILED` or `INVALIDATED`.
- `operation_dispositions` (**DS**): append-only durable result of an execution, one disposition per execution.
- `operation_projections` (**K**): aggregate participation view with separate `technical_status`, `commercial_status`, `financial_status`, token and version.

`semantic_applications` provides a global semantic identity across all cycles, participations, jobs and executions; see [ADR-019](ADR-019-provider-identity-and-late-payment-recovery.md).

## Separate transaction boundaries

### TX-RX

Verify identity/digest, persist provider event and operation, create initial participation/job when needed. No commercial/financial effects.

### TX-CLAIM

Lock J with `FOR UPDATE SKIP LOCKED`; verify it remains effective; invalidate executions with expired leases; increment fencing token and insert new XA with a new lease. Concurrent workers must not both obtain the effective token.

### TX-APPLY

Lock J → XA and verify effective job, fencing token and active lease using PostgreSQL `clock_timestamp()`. Execute business processing following ADR-016's lock order. Persist semantic application and immutable disposition before commit. Stale token, expired lease or replaced job abort the transaction.

A pre-existing semantic identity with equal digest can correspond to known evidence, but **must not by itself determine the current event's business disposition**; [ADR-019](ADR-019-provider-identity-and-late-payment-recovery.md) clarifies when a different valid signed event may progress the operation. Mismatched digest is a conflict with rollback.

### TX-PROJECT

Read committed evidence and update K **without replaying the business operation**. Mark execution PROJECTED/COMPLETED only after projection. A crash between APPLY and PROJECT must recover the prior committed business decision.

**Verified recovery correction:** REV-DEV-01 showed that the earlier process re-invoked business APPLY when reclaiming the **same already-applied job**, and could turn a later captured payment into a refund through an old NO_OP event. The `main` fix uses an existing committed APPLIED/NO_OP disposition for that job to skip business evaluation on technical recovery. It does **not** prohibit a different valid signed provider event from applying a genuine new state transition. [CI #324](https://github.com/dab960405/tickeringsystem/actions/runs/44981366848) verifies the targeted regression.

## Replacement and recovery

Replacing a job sets the previous job to REPLACED/`effective=false`, invalidates active executions and creates a higher-generation job. A partial unique index and fencing prevent old worker effects. A new cycle may create another participation/job, but the global semantic identity remains stable. New provider events can be evaluated under ADR-019 without replaying the old job.

Retries respect backoff and `max_attempts`. Exhaustion records FAILED **technical status**, not a fictitious completed financial operation.

## Parameters, access and consequences

Operational defaults are specified in [F4-01](../phase-4/F4-01-operational-decisions.md) and validated by `OperationProperties`. Workers are internal; no public worker APIs. Evidence must not reveal HMAC signatures, secrets or complete personal information.

Recovery uses PostgreSQL fencing/uniqueness, and technical, commercial and financial statuses remain separate. This decision implements **no Kafka, outbox publisher, SQS or Phase 5 feature**. All confirmed payment/refund processing remains simulated.
