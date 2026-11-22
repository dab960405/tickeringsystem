# Technical decision verification policy — Ticketing

- **Status:** Required engineering review procedure for new decisions and retrospective assessments.
- **Introduced:** 2026-11-14.
- **Scope:** Backend, PostgreSQL, concurrency, security, caching, messaging, Docker, Terraform, AWS and local development infrastructure.
- **Purpose:** Demonstrate practical software-engineering competence in a portfolio project without reinventing standard technology mechanisms or introducing enterprise-scale infrastructure outside the specification.
- **Governance:** A developer recommendation does not imply Tech Lead acceptance. Proposed ADRs require an explicit technical verdict; task-implementation authorization is not architectural approval.

## Two-source verification protocol

1. **Establish the requirement and contract.** Identify the original specification section, accepted ADR, affected component, exact version, immutable Git SHA and scope constraints. Distinguish required behavior from an unverified design hypothesis.
2. **Consult the official primary source.** Read the version-matched documentation for the actual mechanism (Spring Framework/Boot, PostgreSQL, Java, Kafka, MongoDB, Docker, HashiCorp, AWS or GitHub).
3. **Independently double-check.** Where applicable, contrast another maintainer's primary documentation or a normative standard (e.g., Spring + PostgreSQL for transaction atomicity; PostgreSQL + IETF for HTTP conflict semantics; Oracle Java + PostgreSQL for message authenticity and persisted identity). A second page from the same vendor may help, but does not independently validate Ticketing business policy.
4. **Inspect real source and configuration.** Check exact files/functions against the referenced SHA, not comments or diagrams alone. Record conditions such as Spring proxy invocation, implicit foreign-key locks and dependency versions.
5. **Compare alternatives.** Consider retaining the existing mechanism, reusing supported framework/database features or writing something new. Compare complexity, security, operations, reversibility, lock ordering, API/database compatibility and zero-cost requirements.
6. **Execute proof.** Before claiming success, require pertinent regression tests and `./mvnw -B clean verify` with a successful CI tied to the exact tested SHA; use real PostgreSQL/Redis via Testcontainers for concurrency and transactions. Clearly distinguish local tests from CI, and never claim tests that were not executed.
7. **Record and request a verdict.** Document sources, observed evidence, unresolved risks and the **proposed** ADR disposition. Do not change an ADR to accepted before the independent Tech Lead verdict or close a phase without the project owner's formal authorization.

**Stop condition:** If a relevant official source is absent, source and code disagree, or the affected requirement lacks a test, pause the architectural/behavioral change and request the missing technical decision. Do not create workers, migrations, libraries or infrastructure merely to address an unreproduced hypothesis.

## Historical Phase 3 cross-check — ADR-024

**Source SHA inspected at the time:** `d92238a9f7d37dd0127db4029bb4fcb387886457`. The following is **historical evidence**, not a live rereview of current `main`.

| Check | Primary documentation | Repository evidence at historical SHA | Assessment |
| --- | --- | --- | --- |
| One physical PostgreSQL transaction | [Spring 6.2 PROPAGATION_REQUIRED](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/tx-propagation.html); [declarative transactions and rollback](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/rolling-back.html) | `EventCancellationService` has `@Service`/`@Transactional` on externally invoked public `cancel(UUID)`; `EventGuardConcurrencyIT` forces PostgreSQL error and checks rollback | Conforms in tested scenarios; self-invocation and checked exceptions need explicit treatment |
| Lock order and deadlock avoidance | [PostgreSQL 17 explicit locking](https://www.postgresql.org/docs/17/explicit-locking.html), plus Spring transaction rules | `EventLockService.lockExclusive`, `TicketTypeInventoryService.lockAll`, `FOR UPDATE OF r`, `FOR UPDATE OF o`; ADR-016 covers implicit FK locks | E → T → R → O sequence documented; reaudit if FKs/flows change |
| Redis projection after commit | [Spring 6.2 TransactionSynchronization.afterCommit](https://docs.spring.io/spring-framework/docs/6.2.6/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html) | `ReservationRedisProjectionService.deleteAfterCommit` uses transaction synchronization; failed DB TX must not delete cache | Conforms within an active transaction; the helper executes immediately if no synchronization is active |
| Module boundaries | [Spring Modulith module verification](https://docs.spring.io/spring-modulith/reference/verification.html) | `EventCancellationService` references repositories and SQL across packages | A real architectural exception, not an automatically recommended Spring practice; needs Tech Lead acceptance |
| Automated evidence | [Historical CI #318](https://github.com/dab960405/tickeringsystem/actions/runs/43665308382); [F3-01](phase-3/F3-01-closure-review.md) | 390 API unit tests, 171 integration tests, EventGuardConcurrencyIT 10/10 and Spotless/JaCoCo | Proves exercised scenarios, not every possible concurrency interleaving |

**Developer recommendation at that time:** permit only a narrowly documented exception for `EventCancellationService.cancel` while preserving its transaction, locks and tests; **ADR-024 remains PROPOSED**. Prefer module-owned application interfaces if the Tech Lead rejects it, without replacing the workflow with an unnecessary distributed saga.

**Subsequent finding:** AUD-05 covers additional cross-module coordinators beyond `EventCancellationService`; see [pending decisions](audit/PENDING-DECISIONS.md). Do not treat a narrow ADR-024 exception as global approval.

## Historical Phase 4 cross-check — ADR-022 / H84-01

| Check | Primary documentation | Repository evidence and contract | Assessment |
| --- | --- | --- | --- |
| Unique identities and concurrency | [PostgreSQL 17 UNIQUE constraints](https://www.postgresql.org/docs/17/ddl-constraints.html), [ON CONFLICT](https://www.postgresql.org/docs/17/sql-insert.html) | `ProviderEventInboxService.registerOperation/registerEvent` uses `INSERT ... ON CONFLICT DO NOTHING RETURNING id` and compares persisted evidence; V14 uniqueness constraints | Conforms for covered identities; digest changes require a separate contract |
| Webhook authenticity | [Oracle Java 21 Mac](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Mac.html), [MessageDigest.isEqual](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/security/MessageDigest.html) | `PaymentWebhookService` computes HMAC-SHA256 on **exact raw UTF-8 payload** and compares before processing | Suitable for simulated provider; does **not** by itself implement time-based replay rejection |
| HTTP 409 on conflicting evidence | [IETF RFC 9110 `15.5.10](https://www.rfc-editor.org/rfc/rfc9110.html#section-15.5.10), PostgreSQL uniqueness conflict | `DuplicateResourceException` maps to `HttpStatus.CONFLICT` in `GlobalExceptionHandler` | Consistent HTTP contract |
| Stable provider refund reference | Database UNIQUE does not specify refund business rules | `PaymentEvidenceDigest.semantic` includes `providerReference` but excludes status; `ProviderEventInboxService` rejects incompatible evidence for reused `providerOperationId` | Project-specific minimum SIMULATED contract, not a PostgreSQL mandate |
| Early refund and reevaluation | Spring declarative transactions and persisted PostgreSQL identity | `OrderPaymentService.confirmRefund` returns `NO_OP` before a successful capture; a **new signed providerEventId** with same operation identity/digest may legitimately yield `APPLIED` after capture | Historical `OrderPaymentFlowIT` regression verified in CI #309/#318; no automatic financial reconciliation |

**Developer recommendation:** retain the simple simulated provider's immutable digest and explicit newly signed event rule, without silent `null → non-null` enrichment or automatic reconciliation. **ADR-022 remains PROPOSED**.

**Critical later correction:** the distinction between a *new provider event* and *recovery of the very same already-applied job* was violated by REV-DEV-01 and corrected with a durable APPLY/PROJECT regression (CI #324); see [main remediation](audit/MAIN-REMEDIATION-2026-11-21.md). That later test must be included in any new ADR-022 verdict.

## Limitations and current governance

- External sources support underlying mechanisms; **they do not approve commercial policy or a modular exception**.
- The older audit inspected selected services, validators, status mapping, ERD, ADRs and tests at the recorded SHA. It was not an exhaustive formal interleaving proof or a new Docker Compose test.
- Historical claims about tests planned for Phase 5 are superseded by later local smoke evidence; always label source SHA and execution date.
- No real payment gateway, automatic refund reconciler, real email service, Kafka event publisher, Kafka consumer or Mongo notification workflow has been authorized or implemented.
- [Phase 3 issue #5](https://github.com/dab960405/tickeringsystem/issues/5) and [Phase 4 issue #6](https://github.com/dab960405/tickeringsystem/issues/6) require explicit independent decisions before formal closure.
- **Only `main` is currently maintained; historical `dev` references are not instructions to restore a branch. Phase 5 implementation is BLOCKED.**
