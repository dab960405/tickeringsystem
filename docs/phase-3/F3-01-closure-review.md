# F3-01 — Phase 3 technical closure dossier

- **Formal status:** OPEN pending independent Tech Lead verdict. Project-owner architectural and risk dispositions have now been explicitly authorized and recorded in F3-02; formal closure is **not** yet asserted.
- **Current work branch:** `main` only. Legacy references to `dev` are historical and must not be used for new work.
- **Evidence baseline:** `main@236b31f46534f65c1249ff1f4cbd25586dc4938b`, [CI #322](https://github.com/dab960405/tickeringsystem/actions/runs/44974013851) SUCCESS, 407 Java unit tests and 179 integrations, Spotless/JaCoCo.
- **Recovery and expiration fix:** `11dab6d00d8298024a2d10aee4857a0de11f236d`, [CI #324](https://github.com/dab960405/tickeringsystem/actions/runs/44981366848) SUCCESS, 407 unit and 181 integration tests. New `OperationWorkerIT` and `OrderPaymentFlowIT` regressions pass.
- **Subsequent hardening:** `c1368a3b4773ca41f26a0b604bf65122b6771b3c`, [CI #325](https://github.com/dab960405/tickeringsystem/actions/runs/44981594500) SUCCESS: safer HTTP input mapping, UTF-8 byte password validation, Hibernate SQL error log containment and JPA regression.
- **Other ongoing work:** Angular/Python CI and English-language conversion have independent commits; the **final HEAD must be verified** before technical acceptance.
- **Governance issue:** [#5 — Phase 3 and proposed ADR-024](https://github.com/dab960405/tickeringsystem/issues/5). No acceptance is inferred from an open issue or successful CI.

## A. Functional acceptance and evidence

| Criterion | Implementation and test evidence | Assessment |
| --- | --- | --- |
| Stock atomicity and 100 requests competing for 10 tickets | PostgreSQL conditional update, `ReservationConcurrencyIT` and `ReservationHttpConcurrencyIT` each repeat 20 times. Both verified in CI #324 | Tested in specified scenarios |
| Reservation TTL, cancellation and inventory release exactly once | `ReservationExpirationIT`, `ReservationLifecycleConcurrencyIT`, explicit HELD/RELEASED/CONSUMED disposition | Tested |
| One order per reservation; idempotency key, immutable purchase price | Flyway V11, `OrderCreationIntegrityIT`, `OrderPaymentFlowIT` | Tested |
| Signed simulated webhook; invalid signature rejection | Raw UTF-8 payload HMAC-SHA256, `PaymentWebhookControllerTest` and `OrderPaymentFlowIT` | Tested |
| Payment success/failure, late capture and due-refund status | `OrderPaymentService`, ADR-019, `OrderPaymentFlowIT` | Tested |
| Ticket issuance, commercial units and generations | Flyway V16, `CommercialIssuanceIT` | Tested |
| Event cancellation and transaction rollback | `EventCancellationService`; `EventGuardConcurrencyIT` 10/10 in CI #324 | Tested; bounded ADR-024 exception now owner-approved, independent conformity pending |
| Cancelled reservation with attached pending order | REV-DEV-02: include CANCELLED holds in expired-order discovery, no second stock release, preserve late capture financial evidence | New integration test passed CI #324 |
| Durable operation APPLY versus projection-only recovery | REV-DEV-01: reuse committed disposition for the **same job** and do not rerun commercial callback; a new signed provider event is separate | New integration test passed CI #324 |
| HTTP input validation and safe logging | Add fraction rejection, UTF-8 BCrypt limit, real JPA logger regression | CI #325 passed |

These rows show finite executed scenarios, not a proof that all possible transaction interleavings have been enumerated.

## B. Architecture exception and open findings

**Proposed [ADR-024](../adr/ADR-024-event-cancellation-transactional-exception.md)** addresses the deliberate cross-module coordination in `EventCancellationService.cancel(UUID)`:

1. One physical PostgreSQL transaction.
2. Orderly acquisition of E → T → R → O, including implicit FK locks, according to accepted ADR-016.
3. Revalidation after acquiring locks; reserve/return inventory only when held.
4. Never invent payment failure, capture or refund evidence from event cancellation.
5. Rollback all state changes on PostgreSQL exceptions; invalidate Redis only after commit.
6. Any extension of the exception requires a new review.

**AUD-05 is broader than ADR-024:** other coordinators and discovery queries use SQL or repositories across module boundaries. The independent reviewer must expressly determine whether these are narrow warranted exceptions or should use module-owned services, preserving the same transaction and lock order. **ADR-024 was later OWNER-APPROVED under its narrow conditions; ADR-026 separately limits the other Phase 3 boundaries. Independent Tech Lead conformity remains PENDING.**

**AUD-03** Location URI semantics, **AUD-10** HTTP correlation and **AUD-12** cycles also require explicit disposition; not every open item is automatically a Phase 3 blocker. Proposed severities for new findings are distinct from unavailable historical H75–H84 severities.

## C. Verification methodology

Required command for a final closure commit:

```bash
./mvnw -B clean verify
```

Keep Testcontainers backed by disposable PostgreSQL/Redis, preserve Spotless, JaCoCo and all tests. Re-run focused regression tests after any change to locks, state transitions, HMAC, order expiration or rollback. The output must identify the **final main SHA and matching successful GitHub Actions run**. Do not cite a historical run as proof of subsequently edited files.

Historical regression progression:
- `4d381899` added event-cancellation rollback test; #313 failed SQL fixture syntax; `c18d9f2` corrected SQL but #314 failed the stock-baseline assertion; `9d11d833` corrected the assertion and [CI #315](https://github.com/dab960405/tickeringsystem/actions/runs/43654824223) passed all 390 unit and 171 integration tests on old `dev`.
- Modern `main` baseline CI #322 tested 407+179. CI #323 passed 407+181 tests but failed a formatting rule, corrected in `11dab6d`; CI #324 fully passed 407+181.
- The first local smoke evidence exists in historical `docs/LOCAL_RUN.md`; it is not equivalent to a new browser end-to-end verification of the current SHA.

## D. Explicit closure gates

- All specified Phase 3 acceptance tests and new blocking regressions green for the current code.
- Architectural boundary inventory disposition recorded, including an explicit Tech Lead verdict on ADR-024 (or a verified authorized alternative).
- Correctness, safe logging and API/security impacts verified.
- No open blocking findings without an authorized risk disposition.
- A coherent English dossier and traceable evidence on the final `main` SHA.
- **Tech Lead acceptance in issue #5** and **project owner's express formal Phase 3 closure**.

**Historical determination before owner authorization:** Code remediation for priority business-integrity defects was validated by CI #324; at that point architectural decisions and formal review still kept Phase 3 OPEN. Phase 4 closure depends on Phase 3 formal closure. Phase 5 remains BLOCKED.

## E. Tech Lead decision request — draft, not posted

> Please inspect main at the final SHA provided in the delivery report and the updated F3-01 dossier. Approve/reject ADR-024 with precise conditions after considering AUD-05's additional module boundary exceptions. Confirm the Phase 3 functional/integrity acceptance matrix, outstanding finding dispositions, and whether Phase 3 may be formally closed. No status change or merge approval is presumed.

## F3 technical follow-up — 2026-11-22 (developer evidence; not an approval)

The latest code correction within existing Phase 3 contracts addresses **BACK-01** with `EventLockService.lockExclusive` on both ticket-type name mutations. The initial [CI #342](https://github.com/dab960405/tickeringsystem/actions/runs/44986036343) reproduced case-folding duplicate create/rename outcomes with five failures. [CI #344](https://github.com/dab960405/tickeringsystem/actions/runs/44986504339) passed all nine new PostgreSQL Testcontainers cases, 191 integrations, 410 Java units, Spotless and JaCoCo. The existing schema and API remain unchanged; no old migration was rewritten. The later source tree adds create/rename cross-path coverage; use its matching final CI for final verdict.

REV-DEV-01/02, audit logging, DTO/BCrypt validation and BACK-04 remain part of the previously corrected and CI-tested scope. **Historical pending items now superseded by owner dispositions:** AUD-03, AUD-05/ADR-024, AUD-10 and AUD-12 are resolved or scoped for Phase 3 by the 2026-11-22 F3-02 register. The *independent Tech Lead conformity verdict* and final formal closure recording remain outstanding. [Decision register](../audit/PENDING-DECISIONS.md). GitHub [issue #5](https://github.com/dab960405/tickeringsystem/issues/5) remains open. No feedback or issue comments were posted.


**Exact post-remediation code verification:** [CI #348](https://github.com/dab960405/tickeringsystem/actions/runs/44986963218), `main@e318c46f0623ee2cdebaf2dbe8241b8b7ddf78d7`, completed **SUCCESS**: 410 Java unit tests, 195 PostgreSQL/Redis Testcontainers integrations (including 13/13 ticket-type name races), Maven `clean verify`, Spotless and JaCoCo; Angular 19/19 with a production build, Prettier and **real Google Chrome** direct/deep-route independent reload and legacy bookmark with JS/CSS HTTP 200; Python frontend 11/11 and scripts 3/3. This is GitHub Actions evidence, **not** a fresh local Docker or full paid-order browser E2E run. Any later documentation commit requires separate same-SHA CI verification; neither test success nor this report grants Tech Lead acceptance.

## Owner-authorized Phase 3 handoff — 2026-11-22

**Current owner disposition:** [F3-02](F3-02-owner-disposition-2026-11-22.md) records authorization, code remediation and bounded architectural decisions. The owner has approved the narrow ADR-024 event-cancellation coordination and [ADR-026](../adr/ADR-026-bounded-phase-3-cross-module-coordination.md) for overdue-order and candidate-discovery boundaries. **No cross-module exception is assumed for Phase 4 provider/operation services.** Independent Tech Lead conformity remains pending in [issue #5](https://github.com/dab960405/tickeringsystem/issues/5).

| Previously open finding | Phase 3 disposition | Code/test evidence |
| --- | --- | --- |
| AUD-03, `Location` with no public GET | Owner accepted existing 201 Location identifier and response body as nonblocking; no new private API route is invented | Existing controller tests; RFC 9110 §10.2.2 |
| AUD-05 / ADR-024 | Owner approved bounded atomic event cancellation E → T → R → O; no inferred financial evidence | `EventCancellationService`, `EventGuardConcurrencyIT`, PostgreSQL rollback tests |
| AUD-05 / ADR-026 | Owner approved existing synchronous overdue-order coordinator and strictly read-only candidate joins under lock revalidation | `OrderExpirationProcessor`, `OrderRepository`, `ReservationRepository`, `OrderPaymentFlowIT` |
| AUD-10 | Request-scoped correlation filter, safe header allowlist, generated UUID fallback, MDC cleanup, log pattern and CORS support implemented | `CorrelationIdFilterTest` 4 tests and `GlobalExceptionHandlerTest` 2 HTTP path checks; RED [CI #350](https://github.com/dab960405/tickeringsystem/actions/runs/44988342999), behavioral tests passed in #351 (only Spotless red), full GREEN [CI #352](https://github.com/dab960405/tickeringsystem/actions/runs/44988647465) |
| AUD-12/H78 | Owner decided subsequent operation-cycle requirements belong to Phase 4, no Phase 3 API or H78 semantic change | `OperationCycleService`, ADR-018 and F4-05; Phase 4 verdict remains open |

**Gate update:** Priority Phase 3 business-invariant corrections and HTTP correlation regressions have concrete CI evidence, but full end-to-end `clean verify` must pass on the **final** SHA. An independent Tech Lead must approve/reject the bounded architecture and F3 acceptance matrix in issue #5. Owner approval alone cannot be presented as the independent verdict. **Do not mark formal Phase 3 CLOSED or authorize Phase 5 while that gate is open.**


**Validated code baseline:** [CI #352](https://github.com/dab960405/tickeringsystem/actions/runs/44988647465) **COMPLETED SUCCESS** on `main@ff18f1ef59f9d7b92aa607f7ddb04a199bbad1db`, with 410 ticketing-api unit tests and 6 other Java unit tests, 195 PostgreSQL/Redis Testcontainers integration tests, Maven clean verify / JaCoCo / Spotless, Angular 19/19 + Chrome production-route smoke, and 14 Python tests. The subsequent owner-decision documentation commit requires independent same-SHA CI verification; this source-level code evidence is not an independent architectural audit.
