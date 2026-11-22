# Main branch remediation — Phases 3 and 4

- **Baseline:** `main@236b31f46534f65c1249ff1f4cbd25586dc4938b`, [CI #322](https://github.com/dab960405/tickeringsystem/actions/runs/44974013851): 407 Java unit tests, 179 integration tests, Spotless/JaCoCo green.
- **Review date:** 2026-11-21 (America/Bogota).
- **Scope:** Phases 0–4 only; Kafka/outbox relay, notification consumer, Mongo notification storage and other Phase 5 functionality **not authorized**.
- **Source:** [Main audit](AUD-2026-11-21.md), [adversarial report](../ADVERSARIAL_TESTING.md), original specification, current code and Tech Lead issues [#5](https://github.com/dab960405/tickeringsystem/issues/5) and [#6](https://github.com/dab960405/tickeringsystem/issues/6).
- **Governance:** This developer remediation record is not a Tech Lead verdict. Historical H75–H84 severities were not recoverable from the original review and must not be invented.

## Confirmed regressions and corrections

| ID | Finding and original observation | Implementation disposition |
| --- | --- | --- |
| REV-DEV-01 | Recovering PROJECT could execute a business refund callback again after APPLY had already committed NO_OP. | A successful job disposition (APPLIED or NO_OP) is now treated as durable. Retrying the **same job** records a technical NO_OP and resumes projection without calling the business effect. A new independently signed provider event has a different job and can still progress the commercial state. Permanent `OperationWorkerIT` regression added. |
| REV-DEV-02 | Cancelling a reservation linked to a pending order excluded that order from the post-grace expiration scan, leaving it pending. | Candidate selection now includes CANCELLED reservations. The expiration processor resolves the pending order after grace without releasing inventory again. Permanent `OrderPaymentFlowIT` regression covers repeat expiration and late capture. |
| AUD-02 residual | Hibernate can log rejected SQL values before the HTTP exception handler sanitizes them. | Protect the ORM SQL exception logging category and verify it using an actual JPA uniqueness failure captured at the root logger; preserve safe application conflict logging. |
| ADV-03 | Jackson accepted a fractional quantity and coerced it to Integer. | Disable floating-to-integral coercion at deserialization and verify HTTP 400 before the reservation service. This also protects other integral DTO fields. |
| BACK-02 | A Unicode password within 72 Java characters can exceed BCrypt's 72 UTF-8 bytes and produce an unexpected HTTP 500. | Add bean-level byte-aware registration validation with HTTP 400; preserve existing BCrypt hashes and login behavior. |
| ADV-04 | CSP `base-uri 'none'` prevented Angular from honoring the root base on a nested route. | Allow same-origin `base-uri 'self'` and preserve all other protections. Verify nested-route headers, source isolation, and browser refresh separately. |
| UI-NEW-01 | During an in-flight reservation, the buyer could select a different event and then attach the old reservation to the new checkout. | Reject changes to a different selected event while the checkout operation is busy. Add an Angular race regression. Further tests for logout and session transitions must remain explicit. |
| TOOL-NEW-01 | The local smoke client followed HTTP redirects and could forward its bearer token to a different loopback origin. | Disable redirects and add synthetic multi-status redirect regressions without external destinations. |

The first two fixes are validated by [CI #324](https://github.com/dab960405/tickeringsystem/actions/runs/44981366848) on `11dab6d00d8298024a2d10aee4857a0de11f236d`: **407 unit tests, 181 integration tests, Spotless, JaCoCo and BUILD SUCCESS**. CI #323 had passed the tests but failed one Spotless formatting rule; it was corrected in a separate commit. Additional fixes must be associated with their own final SHA/CI before being called verified.

## Pending technical decisions and risks

| ID | Required action | State |
| --- | --- | --- |
| AUD-03 | Review HTTP 201 Location behavior per resource; RFC 9110 does not mandate a GET route for every created resource. Avoid unauthorized API changes. | Needs contract verdict |
| AUD-05 / ADR-024 | Audit cross-module repository access, SQL reads and coordinating writes, including event cancellation and order/payment operation workers. Preserve one physical transaction and ADR-016 lock order. | Tech Lead verdict pending |
| AUD-10 | Decide minimal HTTP correlation ID and MDC scope; Kafka propagation belongs to Phase 5. | Needs approved contract |
| AUD-12 | Recover the original requirement for additional operation cycles. Never infer a new business API from the schema alone. | Needs requirement decision |
| BACK-01 | Reproduce `VIP`/`vip` concurrent name uniqueness before choosing event locking or an approved additive DB index. Do not modify applied V4. | Static risk under investigation |
| BACK-04 | Reproduce JVM-vs-DB expiration worker clock skew and establish the liveness/latency requirement. | Static risk under investigation |
| ADR-022 / H84-01 | Obtain explicit acceptance/rejection of the SIMULATED refund evidence contract, informed by same-job projection recovery. | Tech Lead verdict pending |
| Docs language | Owner expressly superseded the earlier Spanish README/ADR language instruction; all current first-party repository text must be English. See [ADR-025](../adr/ADR-025-english-only-project-policy.md). | Repository-wide migration pending verification |

## Acceptance gates

1. **Correctness:** the previously failing scenarios have durable regression tests; no overselling, double inventory release, duplicate commercial application or fabricated financial refund.
2. **Transactions and security:** PostgreSQL is authoritative; lock ordering, fencing, idempotency, append-only evidence, JWT ownership checks and raw-body HMAC remain intact.
3. **CI coverage:** verify Maven, Angular, Python tooling and non-destructive adversarial regressions on the **same final SHA**. Local browser behavior must be reported separately unless actually exercised.
4. **Documentation:** current branch is `main` only; legacy `dev` commits are historical references. Do not falsify historical tests or overwrite source review material.
5. **Formal closure:** obtain an independent Tech Lead decision on [issue #5](https://github.com/dab960405/tickeringsystem/issues/5) and formal owner approval for Phase 3; only then obtain the verdict on [issue #6](https://github.com/dab960405/tickeringsystem/issues/6) and formal Phase 4 closure.
6. **Phase 5:** remain BLOCKED. Do not implement Kafka topics, producers, outbox publishing, notification consumers or Mongo notification persistence.

## External technical cross-checks

- [Spring Framework 6.2 — transaction propagation](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/tx-propagation.html).
- [PostgreSQL 17 — lock order and deadlocks](https://www.postgresql.org/docs/17/explicit-locking.html).
- [Jackson — ACCEPT_FLOAT_AS_INT](https://github.com/FasterXML/jackson-databind/wiki/Deserialization-Features).
- [OWASP — Password Storage (BCrypt byte limit)](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html).
- [MDN — CSP base-uri](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy/base-uri).
- [Python — urllib redirect handler](https://docs.python.org/3/library/urllib.request.html).
- [GitHub — Node build and test](https://docs.github.com/en/actions/tutorials/build-and-test-code/nodejs).

## Evidence limitations

The independent review used disposable containers and browser/Angular probes; its reports do not assert that the final implementation has been independently browser-tested. The running assistant has no Docker daemon or live local browser in its execution environment. Final production claims require independently recorded tests, CI completion, code review and the Tech Lead's actual written verdict.
