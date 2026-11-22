# ADR-027 — One-time owner-delegated Phase 3 closure gate exception

- **Decision status:** ACCEPTED by delegated project-owner authority, 2026-11-22, **Phase 3 only**.
- **Scope:** Closure governance for F3-01 and GitHub issue #5. This does not authorize changing application code, waiving existing functional tests, redefining any historical finding severity, or accepting Phase 4/5.
- **Related:** [F3-01](../phase-3/F3-01-closure-review.md), [F3-02](../phase-3/F3-02-owner-disposition-2026-11-22.md), [F3-03](../phase-3/F3-03-delegated-owner-closure-2026-11-22.md), [ADR-024](ADR-024-event-cancellation-transactional-exception.md), [ADR-026](ADR-026-bounded-phase-3-cross-module-coordination.md).

## Context

F3-01 and issue #5 originally required a substantive **independent Tech Lead conformity verdict**, followed by the project owner's formal closure. Code remediation and owner decisions were complete, but **no independent Tech Lead verdict had been received**. Repeated developer handoffs and green CI do not constitute independence.

The owner subsequently instructed the developer in this conversation: **"lo que quiero es otorgarte todos los poderes necesarios para cerrar la fase 3"**. This is a delegation of closure authority, not evidence of a Tech Lead review or a personally signed GitHub approval.

## Options considered

1. Preserve the independent review gate: strongest role separation, but closure cannot be completed without an actual independent reviewer. No reviewer was identified/assigned in issue #5.
2. **One-time project-owner procedural exception (selected):** exercise the delegated owner's closure authority and accept the specific absence of an independent Phase 3 verdict as a transparent governance risk. Preserve complete immutable engineering evidence and do not claim Tech Lead approval.
3. Developer self-attestation as an 'independent Tech Lead': **rejected** because the implementation author is not an independent reviewer.

## Decision and explicit boundaries

- **The owner's delegated closure authority supersedes the independent Tech Lead sign-off gate for Phase 3 only.** This is a *governance variance*, not fulfillment of that gate. The independent Tech Lead's technical verdict remains **NOT OBTAINED**, not 'approved', 'passed', or silently presumed.
- Record F3 as **CLOSED BY OWNER-DELEGATED EXCEPTION**, citing the actual immutable source SHA and successful same-SHA CI. Issue #5 must state why the independent-review request is no longer planned as a Phase 3 prerequisite; close it with an accurate reason, not with a fictitious favorable review.
- Preserve the owner-approved, strictly bounded ADR-024 and ADR-026 coupling exceptions and the accepted Phase 3 AUD-03/05/10/12 dispositions. No wider module-boundary waiver is created.
- Do not turn green tests into proof of all transaction interleavings, production security compliance, financial settlement or full paid-order browser end-to-end coverage. Source/test conclusions remain limited to the executed scenarios.
- This **does not** waive the independent Phase 4 Tech Lead verdict, ADR-022/H84-01 requirements, Phase 4 closure, or the independent Phase 5 start authorization. H78 subsequent operation-cycle requirements remain open within Phase 4.
- If a subsequent external audit discovers a reproducible integrity defect, reopen the relevant finding and remediate; this recorded exception is not a license to suppress evidence.
- Any post-closure documentation commit requires its own CI confirmation before the issue is closed. The final SHA and CI are recorded in the issue #5 closure comment.

## Technical evidence and residual risk

- Pre-closure source and owner-decision baseline: [`main@7bd140535abf33a810988bbe718be55c6c0d60ac`](https://github.com/dab960405/tickeringsystem/commit/7bd140535abf33a810988bbe718be55c6c0d60ac).
- [CI #353](https://github.com/dab960405/tickeringsystem/actions/runs/44988996961) **COMPLETED/SUCCESS**: 410 ticketing-api and 6 other Java unit tests; 195 PostgreSQL/Redis Testcontainers integration tests; Spotless, JaCoCo, Maven BUILD SUCCESS; 19 Angular tests, production build/real Chrome route smoke/Prettier; 14 Python tests.
- Original business protections: ADR-016 global lock-order protocol; event cancellation E → T → R → O, single physical PostgreSQL transaction/rollback; post-commit Redis projection; expiration candidate revalidation under locks; provider-financial evidence not invented.
- Residual accepted risks: direct cross-module persistence coupling, sampled rather than exhaustive concurrency coverage, identifier-only `Location` for some resources and no complete paid-order browser E2E claimed.
- **Governance deviation:** independent Phase 3 Tech Lead review was not conducted; this is an accepted one-time **owner** risk, not a third-party conformance endorsement.

## Consequences

F3-01 is formally closed **by owner delegation and the stated procedural variance**, not by an independent Tech Lead sign-off. F4-05 remains **OPEN**, issue #6 remains open and F5 remains **BLOCKED**. This precedent must not be copied to other phase gates without a new explicit owner decision.
