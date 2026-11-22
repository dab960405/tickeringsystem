# Historical local Angular verification

**Execution date:** 2026-11-15. **Historical checkout:** `dev` (the branch was subsequently integrated into `main` and deleted). Tested with the local Java API, PostgreSQL and Redis; Angular 22.2.6, CLI 22.2.7 and Node 24.22.3. All described payments were **simulated**.

**Evidence scope:** this record captures the local tests and walkthrough performed on that date. It is not a claim of fresh browser testing on the current `main` SHA or approval of Phases 3/4. Later adversarial testing identified an Angular deep-link issue and a checkout selection race; subsequent fixes must be validated against their own commits and CI.

## Automated verification performed

| Command | Historical result |
| --- | --- |
| `npm ci --no-fund` | Reproducible clean installation; npm audit reported no vulnerabilities during **that particular execution** |
| `npm test` | 14 tests passed across 4 files |
| `npm run build` | Production build passed without size-budget warnings |
| `npm run format:check` | Formatting passed |
| `python3 -m unittest discover -s frontend -p 'test_*.py' -v` (repository root) | 10 tests passed |
| `git diff --check` | Passed |

The initial bundle was approximately 309 kB, with 86 kB estimated transfer size reported by Angular CLI. Pages were split into lazy-loaded chunks. These are **build estimates**, not production performance measurements.

## Local browser walkthrough against the API

- Registered a synthetic buyer and signed in, returning to the chosen event.
- Purchased two General tickets for USD 90: created a reservation and an order, navigated to the orders screen while retaining checkout state, simulated a successful payment and saw two tickets.
- Reloaded and opened a private page: navigated to sign-in and recovered the persisted purchase from the backend afterward.
- Requested and confirmed a simulated refund: the order became refunded and the list of valid tickets became empty.
- Released a reservation before order creation, then checked updated availability.
- Cancelled a pending order and simulated a failed payment on another order; both orders became cancelled.
- Completed a second purchase for a single USD 45 ticket and copied its code.
- Signed out and signed back in; the valid ticket remained available.
- Checked an empty search, a populated search and the URL filter.
- Inspected responsive layouts at widths of 320, 768, 1024 and 1440 pixels without observed horizontal overflow in the reviewed pages. The keyboard skip link focused the content without losing the URL.

Synthetic demo orders retained for historical inspection:

| Order ID | Observed result |
| --- | --- |
| `8605c6ab-c5e7-45f5-a6ca-954ecfbf283a` | USD 90, paid and subsequently refunded |
| `1d86843b-1495-4e51-82a9-802b1c11cffc` | Cancelled before payment |
| `23bad2e6-7914-4d8d-9974-e4ef7cb538b0` | Payment rejected; order cancelled |
| `758cfe71-aefd-4df5-88d5-643c80706bb9` | USD 45, paid with a valid ticket |

## Defect corrected during the walkthrough

After a payment was confirmed, navigation could destroy `OrderActions` while its asynchronous promise was still pending. The component tried to emit `updated` after destruction, and Angular logged `NG0953`. A regression test reproduced the behavior; the fix checks `DestroyRef.destroyed` before emitting. The regression passed, and the later purchase produced no additional warnings or console errors. The skip link was also checked to ensure it did not reload the SPA.

## Limitations and later changes

The Java implementation was not changed, and the complete Java suite was not rerun as part of this historical frontend migration. The actual ten-minute reservation expiration and the operating system print dialog were not exercised manually. Token renewal was tested with controlled HTTP responses rather than waiting for actual expiry. These checks do **not** establish complete accessibility, load-testing or public deployment readiness.

The subsequent adversarial report reproduced broken nested-route asset loading and an event/checkout selection race on the baseline. Fixes were implemented later; see [the main-branch remediation dossier](../docs/audit/MAIN-REMEDIATION-2026-11-21.md) and matching CI runs. This historical verification record is preserved without retroactively attributing the fixes to the original session.
