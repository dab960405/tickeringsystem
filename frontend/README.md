# Ticketing Angular frontend

A buyer-facing application built with Angular 22.2.6, Angular CLI 22.2.7 and strict TypeScript. It preserves the existing Java API contracts for the event catalog, authentication, reservations, orders, simulated payments, refunds and tickets. The Python server is a **local-only development tool** that serves the compiled Angular bundle and signs simulated payment webhooks.

## Requirements and startup

- Node.js 24 LTS (24.15 or later; tested historical version 24.22.3, see `.nvmrc`).
- npm 11 or 12, Python 3.10 or later, a running Java API, PostgreSQL and Redis.
- A root `.env` file with `PAYMENT_WEBHOOK_SECRET` matching the API configuration.

Install dependencies from the repository root:

```bash
cd frontend
npm ci
cd ..
bash scripts/local.sh ui
```

Open <http://localhost:5173>. The `ui` command builds Angular in production mode and serves the compiled files with the local simulator. Stop with `Ctrl+C`. See [LOCAL_RUN.md](../docs/LOCAL_RUN.md) for starting the backend.

If Node.js is installed outside the `PATH`, specify `NODE_HOME` in the local `.env` file, pointing to the installation directory containing `bin/node`. The `local.sh ui` command prepends its `bin` directory to the process `PATH`. For direct npm commands, add the same directory to your shell `PATH`. A globally installed Angular CLI is not necessary.

For rebuilds during development, keep the local server running and execute `npm run watch` from `frontend` in another terminal. Refresh the browser after changes. The demo does not rely on `ng serve`: the same localhost origin serves Angular, API requests and the simulator, avoiding an extra CORS configuration.

## Structure and design decisions

```text
src/app/
  core/       DTOs, HTTP client, authentication, interceptor, guards and notices
  catalog/    Paginated event search and URL-based filters
  auth/       Registration and sign-in with typed reactive forms
  purchase/   Reservation, order, countdown and simulated payment/refund actions
  account/    Buyer orders and tickets
  app.*       Navigation, composition and application configuration
```

- Standalone Angular components are organized by feature. Pages are lazy-loaded with Angular Router, without NgModules or a separate state-management library.
- `signal` and `computed` handle small shared/local state. Components use `OnPush`, and `@for` uses stable keys. Checkout state persists across internal navigation.
- Typed `HttpClient` DTOs centralize the existing API contract. A functional interceptor sends access tokens only to the local API/simulator routes, shares concurrent token refresh and retries an HTTP 401 once. Network failures do **not** automatically retry mutating operations.
- Typed reactive forms provide explicit validation, loading, empty/error states and duplicate-submission protection. The Java API remains authoritative for quantities and financial amounts.
- Searches use `switchMap` and `takeUntilDestroyed`. Leaving the checkout view cancels its countdown. Asynchronous UI actions check `DestroyRef` before emitting after navigation. Checkout selections are protected while a reservation mutation is in progress to prevent mixing different events.
- Access tokens remain in memory. Reloading requires sign-in; orders and tickets persist in the backend. Route guards assist navigation, but the API enforces authorization.
- Angular interpolation is used without `innerHTML` or sanitizer bypasses. No HMAC secret is included in the browser bundle.
- Size budgets, AOT compilation, strict TypeScript/templates, Prettier and `package-lock.json` support reproducible builds.
- Canonical UI routes are English (`/events`, `/login`, `/register`, `/orders` and `/tickets`). Temporary redirects for historical Spanish bookmarks are kept explicitly for compatibility; they are not the canonical navigation language.

## Buyer journey

1. Find an event and choose **View tickets**.
2. Register or sign in and return to the selected event.
3. Choose a ticket type and quantity, reserve tickets, and create an order before the countdown expires.
4. Simulate a successful payment to receive tickets, or simulate a failure to verify rejection.
5. Open **My tickets** to copy ticket codes or use the browser print dialog.
6. Under **My orders**, cancel a pending order or request and simulate a refund. A confirmed refund invalidates its issued tickets.

Before an order exists, the buyer can release their reservation. After creating an order, the buyer uses order cancellation rather than orphaning the pending order. The idempotency key is preserved when retrying order creation. Payment amounts and currency come from an API response accessed with the buyer's token, never from financial fields supplied by the browser.

## Verification

The historical local browser walkthrough and its limitations appear in [VERIFICATION.md](VERIFICATION.md). The current GitHub Actions pipeline also runs Java, Angular and Python checks; verify the **matching SHA and successful run** instead of assuming historical local results apply to later changes.

From `frontend`:

```bash
npm test
npm run build
npm run format:check
python3 -m unittest discover -s . -p 'test_*.py' -v
```

Angular tests use Vitest, TestBed and HttpTestingController for concurrent refresh, token scoping, sign-out, forms, order idempotency, duplicate reservations, completion handling and component destruction after navigation. Additional tests protect checkout selection during in-flight reservations. Python tests check simulated signing and ownership, retries, localhost origin restrictions and safe serving of compiled files and SPA routes. They do not require Docker, though Python binds an ephemeral localhost port. The Java backend has separate integration tests with Testcontainers.

## Official documentation consulted

Sources documented for the original frontend implementation on 2026-11-15. Effective versions are pinned in the lockfile.

| Decision | Official source |
| --- | --- |
| Compatible Node, TypeScript and RxJS | [Angular version compatibility](https://angular.dev/reference/versions) |
| Feature-based code layout and focused components | [Angular style guide](https://angular.dev/style-guide) |
| Simple reactive state | [Angular signals](https://angular.dev/guide/signals) |
| Reactive and typed forms | [Reactive forms](https://angular.dev/guide/forms/reactive-forms) and [typed forms](https://angular.dev/guide/forms/typed-forms) |
| Cross-cutting HTTP authentication | [Angular interceptors](https://angular.dev/guide/http/interceptors) |
| Routes and lazy loading | [Angular routing](https://angular.dev/guide/routing/define-routes) |
| Cleanup on view destruction | [Angular DestroyRef](https://angular.dev/api/core/DestroyRef) |
| Safe rendering and interpolation | [Angular security](https://angular.dev/best-practices/security) |
| HTTP and component tests | [Angular HTTP testing](https://angular.dev/guide/http/testing) and [testing overview](https://angular.dev/guide/testing) |

## Scope and deployment boundary

This is a localhost buyer demo, **not a production payment gateway**. It does not implement an administrative dashboard, venue-access scanner, real email or real payments. Tickets display their actual API codes; no QR format is invented. Existing order/ticket DTOs omit the event title, so purchase IDs and ticket identifiers distinguish them.

`dev_server.py` binds only to `127.0.0.1` and must not be deployed publicly. `FRONTEND_PORT` and `TICKETING_API_PORT` configure the local ports. HMAC secrets stay in Python and are not sent to Angular. Static files are restricted to `dist/ticketing-ui/browser`; neither source files nor `.env` are served. Server-side rendering is intentionally absent from this local demo.
