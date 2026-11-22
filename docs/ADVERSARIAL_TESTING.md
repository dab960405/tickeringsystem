# Historical local adversarial testing — 2026-11-15

Controlled adversarial probes were executed against the local API and frontend, with additional fault injection using disposable PostgreSQL/Redis instances. This **historical run reproduced four consistency or functionality defects**. These were **not four unauthorized accesses**: unauthorized resource access, forged signatures and attempted privilege escalation were rejected.

**Historical backend baseline:** former `dev` branch, commit `76a0fbb2380d009e13df75e1ab7e9a80f14b69e7`. The Angular frontend and local test tools were still uncommitted work at the time. This report records discovery and tests; **the original review did not correct production code, public contracts or migrations**. The project subsequently integrated fixes for these defects into `main`. Do not interpret older FAIL observations as present failures after the remediation commits; verify the latest matching CI and regressions.

## Historical results and evidence

| Group | Passed | Failed | Inconclusive | Historical local evidence |
| --- | ---: | ---: | ---: | --- |
| HTTP: 16 scenarios, 219 requests | 15 | 1 | 0 | [Markdown](../reports/adversarial/run-20261115-02/http.md), [JSON traces](../reports/adversarial/run-20261115-02/http.json) |
| Isolated integration: 14 tests | 12 | 2 | 0 | [Markdown](../reports/adversarial/run-20261115-01/integration.md), [JSON](../reports/adversarial/run-20261115-01/integration.json), [Maven log](../reports/adversarial/run-20261115-01/integration.log) |
| Real browser: 2 checks | 1 | 1 | 0 | [Browser and asset observations](../reports/adversarial/run-20261115-02/browser.json) |
| Harness self-tests | 2 | 0 | 0 | `python3 -m unittest discover -s scripts -p test_adversarial.py -v` |

Files generated under `reports/adversarial/` are ignored by Git and created afresh for each execution. The historical paths above refer to **local evidence**, which may not be present in a fresh checkout. This document preserves the findings within versioned project documentation. Browser observations were captured using a computer-use session, not by the Python harness; the historical `browser.json` identifies that provenance.

The very first HTTP run at `run-20261115-01/http.json` incorrectly required HTTP 401 when `X-Signature` was absent. Spring rejected the missing mandatory header with HTTP 400 and did **not** apply payment. The **test expectation** was corrected and all 16 cases rerun as `run-20261115-02`. That first mismatch was **not** classified as a vulnerability.

## Defects reproduced in the historical run

### REV-DEV-01 — Recovering PROJECT reevaluated an older refund

**Severity: HIGH proposed**, retaining the original audit's proposed classification pending the independent Tech Lead verdict.

Reproduction was performed in `OperationWorkerIT` using [this additional historical test fixture](../scripts/adversarial-fixtures/recovery-projection.java.txt):

1. Create a pending order.
2. Receive refund evidence and complete APPLY: disposition is `NO_OP` because capture has not occurred. Interrupt before PROJECT.
3. Process a valid capture: payment becomes `SUCCEEDED` and one ticket is issued.
4. Recover the original job with `processJob` **without any new provider event**.

**Expected:** payment remains `SUCCEEDED`; recovering PROJECT must not cause another commercial effect. **Observed in the historical run:** payment became `REFUNDED`, failing that assertion. The test stopped at its first failure: later assertions for tickets, fencing and a newly signed refund event did not execute.

Root cause found: `PaymentOperationService.processJob` always invoked APPLY before PROJECT; `OperationJobService.claim` allowed a previously applied job to be claimed; `SemanticApplicationService.apply` invoked the commercial callback before checking persisted application identity. The **old event** was therefore reinterpreted against a later financial state. No actual money movement or duplicate charge occurred: the provider was simulated.

The fixture deliberately interrupted between two already committed transactions, rather than terminating a JVM. This models a durable state the recovery mechanism must handle. ADR-018 requires **projection-only recovery after committed APPLY**, not business reevaluation. No new infrastructure is necessary.

**Later correction:** `main` added durable APPLY/PROJECT reuse and the persistent worker regression; see [main remediation](audit/MAIN-REMEDIATION-2026-11-21.md). Confirm the new CI rather than relabeling this historical failure.

### REV-DEV-02 — Pending order not expired after direct reservation cancellation

**Severity: MEDIUM proposed**, retaining the historical proposal.

Reproduction was performed in `OrderPaymentFlowIT` with [this historical fixture](../scripts/adversarial-fixtures/cancelled-reservation.java.txt):

1. Create a reservation and a pending order belonging to the same buyer.
2. Cancel the reservation directly, releasing its stock.
3. Move `expires_at` three minutes into the past **only in the disposable test database**.
4. Invoke the expiration service twice.

**Expected:** resolve the overdue order without releasing inventory twice. **Observed:** order `PENDING_PAYMENT`, payment `PENDING` and reservation `CANCELLED` remained; no tickets existed and stock was fully released. The expected `EXPIRED` order transition did not occur.

`OrderRepository.findExpiredPendingOrderCandidates` selected only `ACTIVE` or `EXPIRED` reservations, and `OrderExpirationProcessor` returned early for `CANCELLED / RELEASED`. Repeated sweeps could not resolve the order. Later fixture assertions for late capture did **not** run because an earlier assertion failed; this report does not claim to have verified them.

The remediation must define consistent order/reservation resolution with exact-once inventory release.

**Later correction:** the main branch includes a specific business-state fix and regression; consult [main remediation](audit/MAIN-REMEDIATION-2026-11-21.md) and successful matching CI.

### ADV-03 — Fractional JSON quantity silently coerced to integer

**Severity: LOW proposed** for input validation. This did **not** demonstrate free purchases, overselling or price manipulation.

With an authenticated buyer and valid ticket type, POST:

```json
{"ticketTypeId":"VALID_TICKET_TYPE_UUID","quantity":1.5}
```

**Expected:** HTTP 400 because quantity was not an integer. **Observed:** HTTP 201 with quantity 1. `ReservationRequest` bound the field to `Integer` and validated `@Positive`; Jackson had already coerced the fractional input to an integer **before** validation. The harness cancelled the created hold, restoring stock to its original 20 units.

The initial run already rejected zero, negatives, null, Boolean, int overflow and quantities over the per-order maximum. The correction must reject fractional JSON numbers during deserialization while preserving those other constraints.

**Later correction:** main added strict coercion and a persistent HTTP regression; verify the relevant CI.

### ADV-04 — Direct opening/reloading a nested event route displayed a blank Angular page

**Severity: MEDIUM proposed** for loss of direct access to checkout.

1. Open `/` and navigate from the catalog to an event: worked.
2. Open historical `/eventos/{id}` in a new tab or reload the nested page: blank Angular root.

The browser showed an empty `<app-root>` and a script requested under `/eventos/main-5YBPYVWV.js`. That URL returned 404; the equivalent root script `/main-5YBPYVWV.js` returned 200.

The page specified `<base href="/">` but the local server sent CSP `base-uri 'none'`, which blocks using the HTML base element. Relative asset URLs were resolved below `/eventos/` instead of at site root. This matches [MDN CSP base-uri](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy/base-uri). The fix must make SPA base and CSP compatible while retaining the other protections.

**Later correction:** main uses a same-origin CSP `base-uri 'self'`, adds a regression and migrates canonical routes to English (`/events/:id`) with legacy redirects preserved. The historical original `/eventos` route remains a reference, not the current canonical UI language.

## Protections that withstood the historical probes

- Foreign buyer order read/cancel/payment, reservation cancellation or order creation: five 404 rejections, no ticket issuance or mutation of a foreign order.
- Anonymous request, JWT with altered role and refresh token passed as access token: HTTP 401. Attempted registration with `role: ADMIN` created a `USER`; creating an event returned 403.
- Buyer-provided price/currency could not overwrite the order's USD 45 snapshot.
- Missing signature: 400. Fake signature or modified body with unchanged signature: 401. A later valid signed control event issued one ticket.
- Validly signed but inconsistent amount/currency: 422 and no tickets. These probes had access to a **synthetic provider secret** to emulate an inconsistent provider; they do not show that an anonymous browser can produce valid signatures.
- Six concurrent order requests with the same idempotency key: one order and 201/200 replies. Same key with another reservation: 409.
- Eight duplicate captures, six duplicate refunds and eight events sharing commercial identity: effects were unique. Contradictory identity data returned 409.
- Twenty simultaneous reservations against two units: two succeeded and eighteen returned 409. Stock reached zero while held and returned to two after releasing both reservations.
- Three HTTP races between capture and cancellation had coherent outcomes. Cancellation won in all three historical runs: zero tickets, stock released and `refundStatus: REQUIRED` after late capture. This did **not** observe both possible interleavings.
- Local proxy dropped a response after commit: first internal result 201, client saw no response, retry returned 200 with the same order ID and only one order persisted.
- Early refund → capture → same-event replay and late failure left the purchase paid with one ticket. This did **not** reproduce interrupted APPLY/PROJECT, which needed the separate isolated integration fixture.
- Foreign origin, missing origin or unauthenticated simulator requests were rejected (403/401). The authorized simulator ignored the amount supplied by the browser and used the owned order's API response.
- Private files, source code and traversal attempts against the local server returned 404; a foreign Host returned 403.
- Three SQL-looking search strings were interpreted literally and returned zero results, without widened access or HTTP 500.
- XSS payload `<img ... onerror=...>` in description rendered as literal text; no actual `img[onerror]` nodes were created. This matches the Angular interpolation and [Angular security](https://angular.dev/best-practices/security).
- Disposable Redis outage tests exercised PostgreSQL fallback/recovery, fail-closed sessions, local access JWT validation, bounded stale cache TTL and Lettuce timeouts.
- Five isolated integration checks covered confirmation/cancellation/expiration, two workers and rollback if inventory restoration failed.

## Running the probes again

With API, frontend and trusted `.env` configured:

```bash
bash scripts/local.sh adversarial
```

To include isolated Java integration cases with local Java 21, Docker and Maven dependencies:

```bash
bash scripts/local.sh adversarial --with-integration
```

Or run the isolated integration harness alone:

```bash
python3 scripts/adversarial-integration.py --output reports/adversarial/integration-new
```

`--api`/`--frontend` accept other **loopback-only** ports; remote destinations, URL credentials and redirects are rejected. `--output` must select a fresh directory so existing reports are not overwritten.

Exit statuses: **0** all executed checks passed; **1** at least one finding; **2** execution incomplete/inconclusive. **The old historical expectation of status 1 does not establish the result of the newly corrected main branch. Re-execute to determine today's status.**

The HTTP harness uses only Python's standard library, creates synthetic events and buyer accounts and **retains test purchases** for inspection. Events use `Adversarial <runId>` for recognition. It does not delete existing data. Isolated Java fixtures run against a temporary source copy and disposable PostgreSQL/Redis; they are historical stress/reproduction aids, not replacements for the permanent CI regressions already integrated into the normal suites.

Optional seed customization: `SMOKE_ORGANIZER_EMAIL`, `SMOKE_ORGANIZER_PASSWORD` and `SMOKE_VENUE_ID`. `PAYMENT_WEBHOOK_SECRET` is read from the local environment and never printed. Traces contain HTTP method, route, status and duration, **not** login bodies, passwords, tokens or signatures.

## Limitations of the historical evidence

These are bounded scenarios, not a comprehensive security certification, production penetration test or formal proof. The historical run did not test real payments, sustained internet load, functional Kafka/Mongo workflows, every third-party dependency, all XSS/SQL surfaces or all possible concurrent interleavings. The complete Java suite of 567 tests was **not** rerun during this original adversarial delivery; only the listed targeted tests were executed. Later main CI runs are independent evidence.

After the old probes, the API returned HTTP 200 at `/actuator/health` and the frontend HTTP 200 at `/`. The frontend server process stopped after some browser checks and was restarted; no evidence attributes that process exit to a vulnerability. The blank nested-route page was observed while the server itself was responding, with independent incorrect-asset URL evidence.

For the original 2026-11-15 state, REV-DEV-01 was the highest-integrity priority and ADV-04 the most visible demo blocker, followed by REV-DEV-02 and quantity input validation. Each was subsequently treated in main without introducing Phase 5 infrastructure. Formal Tech Lead phase approvals remain distinct from these technical fixes.
