# Running Ticketing locally

## Guided API smoke test

On Linux/macOS with Bash and Python 3, from the repository root:

```bash
bash scripts/local.sh init
bash scripts/local.sh doctor
bash scripts/local.sh infra
bash scripts/local.sh run
```

- `init` creates `.env` with random secrets and mode 600; it leaves an existing file untouched.
- `infra` starts **only PostgreSQL and Redis**, which are sufficient for the currently implemented Phases 0–4 API.
- `run` packages the application without running tests and starts the API in the foreground.
- `bash scripts/local.sh test` runs the complete `./mvnw -B clean verify` suite with Testcontainers.

With the API running, open a second terminal and execute:

```bash
bash scripts/local.sh smoke
```

The smoke script creates a synthetic future event and two buyer accounts. It checks authorization, stock/reservation transitions, idempotent order creation, HMAC signing, duplicate capture/refund processing, ticket issuance/invalidation and refresh-token revocation. It uses the development organizer and venue seeded under an **explicit `dev` Spring profile**; it preserves synthetic records for inspection and never prints credentials, tokens or signatures.

To select your own local fixtures, export `SMOKE_ORGANIZER_EMAIL`, `SMOKE_ORGANIZER_PASSWORD` and `SMOKE_VENUE_ID`. `TICKETING_BASE_URL` can select a **localhost-only** HTTP port. The smoke client rejects redirects so it cannot forward a bearer token to another origin.

Stop the API with `Ctrl+C`. `bash scripts/local.sh stop` stops PostgreSQL and Redis **without deleting persistent data**. Full `infra` Compose includes Kafka/MongoDB definitions for later phases, but these are **not required** for current API validation.

## Buyer interface

The Angular 22 client requires Node 24 LTS (24.15 or later) and npm. After starting PostgreSQL, Redis and the API:

```bash
cd frontend
npm ci
cd ..
bash scripts/local.sh ui
```

Open <http://localhost:5173>. The UI supports registration, sign-in, event search, ticket-type/quantity selection, reservation, order creation and simulated successful/failed payments. **My orders** supports pending-order cancellation and simulated refund requests; **My tickets** displays currently valid tickets and their codes, with copy/print actions.

**No real payments are made.** The local Python gateway signs simulated webhook payloads using the same `.env` secret as the API. The HMAC secret never enters the browser. Do not enter payment card data. Session tokens remain in memory; reloading the page requires signing in again, but purchases/tickets remain stored by the backend.

`ui` builds Angular; Python serves the compiled assets and proxies API requests. If Node is outside `PATH`, set `NODE_HOME` in `.env`; the script adds `NODE_HOME/bin`. The server binds only to localhost and is **not suitable for production deployment**. `FRONTEND_PORT` and `TICKETING_API_PORT` select local ports. Stop with `Ctrl+C`.

For architecture, test commands and official sources see the [frontend guide](../frontend/README.md).

## Prerequisites

- Java 21.
- Node 24 LTS (24.15+), npm, Python 3.10+ for the frontend.
- Maven 3.9 or newer (prefer the checked-in Maven Wrapper).
- Docker Engine/Desktop, Docker Compose v2 and a working Docker daemon for Testcontainers.
- Free local ports: PostgreSQL 5432, Redis 6379, API 8080; Kafka 9092 and MongoDB 27017 are needed **only if their later-phase containers are deliberately started**, and all may be overridden in `.env`.

## Environment setup

The **recommended** path is `bash scripts/local.sh init`, which safely creates random secrets and a private `.env`. To prepare the file manually, copy the example:

```bash
cp .env.example .env
```

PowerShell:

```powershell
Copy-Item .env.example .env
```

Replace placeholder passwords and secrets. Generate a JWT secret of at least 32 random bytes:

```bash
openssl rand -base64 32
```

Assign it to `JWT_SECRET`, and generate a **different independent** random value for `PAYMENT_WEBHOOK_SECRET`. The API rejects obviously weak HMAC secrets (fewer than 32 characters, known placeholders or repeated single characters), but length validation alone cannot establish entropy. Never commit `.env`.

Docker Compose automatically reads `.env` for interpolation. Java launched directly on the host needs those variables in its process environment.

Bash / Linux / macOS:

```bash
set -a
source ./.env
set +a
```

PowerShell:

```powershell
Get-Content .env | ForEach-Object {
  if ($_ -match '^\s*([^#][^=]*)=(.*)$') {
    [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), 'Process')
  }
}
```

Only source a **trusted, personally controlled** environment file. Reload variables in each new terminal. For local demo development choose `SPRING_PROFILES_ACTIVE=dev` **explicitly** (the example contains that setting). With no active profile, only structural migrations are loaded; demo users are not inserted. Production deployments require their own profile and clean environment. Switching a profile does **not** remove previously seeded accounts.

## Starting infrastructure

To start only the required services, prefer:

```bash
bash scripts/local.sh infra
```

Alternatively, the following starts **all four** Compose infrastructure services, including Kafka and MongoDB scaffolding that is not needed by Phases 0–4:

```bash
docker compose --profile infra up -d
docker compose ps
```

Host ports bind to `127.0.0.1`. Kafka retains its internal listener `kafka:29092`. Previously created containers must be recreated to apply host bind changes, while preserving named volumes. Do not run destructive volume removal.

When starting the full profile, check that all four services report `healthy` before using them:

```bash
docker compose ps
docker compose logs --tail=100 postgres redis kafka mongodb
```

These infrastructure definitions are **not** a Kafka/MongoDB application implementation and do not authorize Phase 5.

## Executing tests

Different commands validate different scopes:

```bash
# Surefire unit tests only (*Test.java):
./mvnw test

# Maven verify without Failsafe integration tests (*IT.java):
./mvnw clean verify -DskipITs

# Full verification: unit + integration tests, JaCoCo and Spotless:
./mvnw -B clean verify
```

- `test` does not run the Failsafe integration-test lifecycle.
- `clean verify -DskipITs` excludes integration tests and cannot demonstrate Testcontainers correctness.
- `clean verify` runs integration tests against disposable Testcontainers PostgreSQL and Redis and enforces Spotless plus JaCoCo line coverage in `service`/`domain` packages.

The GitHub Actions workflow also runs Angular and Python tests. A **green run against the exact source SHA** is required before a claim of whole-repository CI success.

## Packaging and starting the Java API

With environment variables exported and PostgreSQL/Redis available:

```bash
./mvnw -pl ticketing-api -am package -DskipTests
java -jar ticketing-api/target/ticketing-api-0.1.0-SNAPSHOT.jar
```

The Maven command packages shared module dependencies but deliberately skips tests. Java listens on `TICKETING_API_PORT` (default 8080). Keep the terminal open to view diagnostics.

Check health from a separate terminal:

```bash
curl -i http://localhost:8080/actuator/health
```

When Actuator and its reported dependencies are healthy, expect HTTP 200 and `"status":"UP"`. Adjust the port for your configuration. API documentation is available at:

- Swagger UI: <http://localhost:8080/swagger-ui.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>

## Relevant Phase 4 parameters

| Variable | Default | Meaning |
| --- | --- | --- |
| `TICKET_TYPES_CACHE_TTL` | `5m` | Ticket-type metadata cache TTL; Spring duration formats such as `30s` or `5m` |
| `LOGIN_MAX_ATTEMPTS` | `5` | Maximum login attempts per IP/email in a fixed window |
| `LOGIN_WINDOW_SECONDS` | `60` | Login rate-limit window length in seconds |

API Redis command timeout is 500 ms in `application.yml`. Outage integration tests also exercise Lettuce command and connect timeouts configured specifically for the test context.

## Generating the exact webhook HMAC for flow.http

The `POST /api/v1/payments/webhook` endpoint requires `X-Signature` containing the lowercase hexadecimal HMAC-SHA256 of the **exact raw request body**. VS Code REST Client does **not** compute it; generate it separately and copy the digest into `@webhookSignature` in [flow.http](flow.http).

With `PAYMENT_WEBHOOK_SECRET` exported, substitute the real order `id`, `totalAmount` and `currency` from the API response. A single seeded General ticket costs USD 45.00, but the actual order total depends on type and quantity. `PAYLOAD` must match the outgoing JSON **byte for byte**, including property order, spaces and the absence of a trailing newline:

```bash
PAYLOAD='{"orderId":"REPLACE_WITH_ORDER_UUID","status":"SUCCEEDED","providerReference":"smoke-provider-0001","providerEventId":"8f0d3f3d-8a0c-4f1d-a42a-b0f0a55f1001","providerOperationId":"smoke-operation-0001","operationKind":"PAYMENT_CAPTURE","amount":REPLACE_WITH_ORDER_TOTAL_AMOUNT,"currency":"REPLACE_WITH_ORDER_CURRENCY"}'
printf '%s' "$PAYLOAD" | openssl dgst -sha256 -hmac "$PAYMENT_WEBHOOK_SECRET"
```

Copy only the hexadecimal digest shown after `(stdin)=`. Re-sign after **any** raw-body change. The digest itself is not the secret; **never publish `PAYMENT_WEBHOOK_SECRET`**.

## Historical validation provenance

On **2026-11-15**, a Linux Mint host with Java 21 and Docker 29 started PostgreSQL/Redis with Compose, applied Flyway V1–V16 and the development seed, and started the API on port 8080. `scripts/local-smoke.py` recorded **34 HTTP checks** against old `dev` commit `76a0fbb`. A separate full `clean verify` ended in `BUILD SUCCESS` with **567 tests**, no failures/errors/skips and valid JaCoCo/Spotless checks.

This **historical** evidence covers the main buyer purchase/refund path on that source SHA. It is not evidence of current `main` browser verification, all infrastructure services, future-phase functionality, or adversarial recovery checks introduced afterward. Consult Surefire/Failsafe reports and GitHub CI for any later SHA.

## Local adversarial checks

With the API and frontend running, run:

```bash
bash scripts/local.sh adversarial
```

Adding `--with-integration` uses disposable Testcontainers to exercise the recovery, expiration and Redis-outage cases in addition to the local HTTP probes. The harness uses synthetic fixtures and writes JSON/Markdown reports to `reports/adversarial/`. An exit status of **1** indicates a reported failure. Read [adversarial evidence and limitations](ADVERSARIAL_TESTING.md) and the [remediation dossier](audit/MAIN-REMEDIATION-2026-11-21.md).

The older report documents defects discovered on an earlier SHA; subsequent fixes must be checked against their own regression tests and CI, not assumed from historical log files.
