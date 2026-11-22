# Phase 2 authorization matrix

**Historical status:** Phase 2 was formally closed on 2026-11-13 with explicit project-owner authorization. CI run #154 validated isolation scenarios against SHA `06490392e86866094ee9dddbff450f4e76e646ad`; run [#43545520476](https://github.com/dab960405/tickeringsystem/actions/runs/43545520476) validated the pre-closure HEAD `7146517ef80f3fe350a57d00e272f66b59ffc923`.

**Sources of truth:** `SecurityConfig`, service-level `@PreAuthorize` declarations and associated HTTP/integration tests. This matrix describes the effective design; it does not replace test execution against the current SHA.

## Conventions

- **Public:** the HTTP filter does not require JWT; additional business validation still applies.
- **ADMIN, ORGANIZER, USER:** Spring Security authorities `ROLE_ADMIN`, `ROLE_ORGANIZER` and `ROLE_USER`.
- **Owner:** the JWT `sub` must match the persisted owner of the resource.
- **Concealed 404:** both foreign-owned and nonexistent private resources produce HTTP 404, preventing existence disclosure.
- **401:** authentication is missing or invalid.
- **403:** authentication is valid but lacks a required role; ownership-concealment rules may return 404 for foreign resources.

## Routes and permissions

| Method | Route | Access | Additional restriction or expected result |
| --- | --- | --- | --- |
| POST | /api/v1/auth/register | Public | Registration; the server determines the user's role |
| POST | /api/v1/auth/login | Public | Valid credentials; rate limiting may return 429 |
| POST | /api/v1/auth/refresh | Public | Requires an active refresh token; invalid/revoked token 401; unavailable Redis 503 |
| POST | /api/v1/auth/logout | Public at HTTP filter | Revokes the submitted refresh token; an access JWT is not required |
| POST | /api/v1/payments/webhook | Public at HTTP filter | HMAC signature is validated in the payment layer; invalid signature 401 |
| GET | /api/v1/events | Public | Published catalog only |
| GET | /api/v1/events/{id} | Public | Published events only; nonexistent/unpublished 404 |
| POST | /api/v1/events | ORGANIZER, ADMIN | Server assigns organizerId from JWT |
| PUT | /api/v1/events/{id} | ORGANIZER, ADMIN | ORGANIZER must own the event; foreign/nonexistent 404; ADMIN may manage any existing event |
| POST | /api/v1/events/{id}/publish | ORGANIZER, ADMIN | ORGANIZER must own the event; foreign/nonexistent 404 |
| POST | /api/v1/events/{id}/cancel | ORGANIZER, ADMIN | ORGANIZER must own the event; foreign/nonexistent 404 |
| POST | /api/v1/events/{eventId}/ticket-types | ORGANIZER, ADMIN | ORGANIZER must own the event; foreign/nonexistent 404 |
| PUT | /api/v1/ticket-types/{id} | ORGANIZER, ADMIN | ORGANIZER must own the associated event; foreign/nonexistent 404 |
| GET | /api/v1/venues | Public | Public read |
| GET | /api/v1/venues/{id} | Public | Public read; nonexistent 404 |
| POST | /api/v1/venues | ADMIN | USER/ORGANIZER 403 |
| PUT | /api/v1/venues/{id} | ADMIN | USER/ORGANIZER 403 |
| DELETE | /api/v1/venues/{id} | ADMIN | USER/ORGANIZER 403 |
| POST | /api/v1/reservations | USER | Reservation created for JWT `sub` |
| DELETE | /api/v1/reservations/{id} | USER | Only the owner's reservation; foreign/nonexistent 404 |
| POST | /api/v1/orders | USER | Order and reservation are bound to JWT `sub` |
| GET | /api/v1/orders | USER | Lists only orders belonging to JWT `sub` |
| GET | /api/v1/orders/{id} | USER | Read by ID and owner; foreign/nonexistent 404 |
| POST | /api/v1/orders/{id}/cancel | USER | Cancellation is performed for the authenticated owner |
| GET | /api/v1/me/tickets | USER | Only the authenticated user's tickets |

## Infrastructure endpoints

| Route | Access | Notes |
| --- | --- | --- |
| /error | Public | Framework error handling |
| /swagger-ui.html, /swagger-ui/**, /v3/api-docs/** | Public | OpenAPI documentation |
| /actuator/health | Public | Health check |
| /actuator/prometheus | Public route if exposed | Metrics endpoint requires actual registry and actuator exposure before it exists; restrict access before any public deployment |

All unlisted routes are subject to `anyRequest().authenticated()`. Update this matrix when new endpoints are introduced.

## Existing evidence and test gaps

| Area | Identified evidence | Scope |
| --- | --- | --- |
| Login, JWT issuance, refresh and logout | `AuthenticationFlowIT` with real tokens | Exists |
| Event creation with real ORGANIZER JWT | `AuthenticationFlowIT` | Exists |
| Venue role restrictions | `VenueControllerTest` | HTTP tests with simulated authentication |
| Event and ticket-type role restrictions | `EventControllerTest`, `TicketTypeControllerTest` | HTTP tests with simulated authentication |
| USER authorization on orders/reservations/tickets | `OrderControllerTest`, `ReservationControllerTest`, `TicketControllerTest` | HTTP tests with simulated authentication |
| Isolation between two organizers using real JWTs | `AuthenticationFlowIT.should_hideForeignOrganizerEventAsNotFound_usingTwoRealAccessTokens` | Included in successful historical CI #154 Failsafe run |
| Order/reservation isolation across two real buyers | `AuthenticationFlowIT.should_isolateReservationsAndOrdersBetweenTwoRealUserTokens` | Exercises foreign order GET/cancel and reservation cancellation; owner still has read access |
| Complete method × role × resource-state matrix | No single exhaustive suite | Potential incremental improvement; not a proof of an existing bypass |

## Historical Phase 2 acceptance criteria

1. Every functional route has a method and role classification.
2. Owner-scoped mutations derive the user ID from JWT, never a client-supplied arbitrary owner ID.
3. Private foreign-owned resources cannot be read or changed; the documented concealed 404 applies.
4. Integration tests use two real authenticated identities to prove resource isolation.
5. `./mvnw -B clean verify` succeeded in CI for the tested source SHA. Historical run #154 validated `06490392e86866094ee9dddbff450f4e76e646ad`; run #43545520476 validated `7146517ef80f3fe350a57d00e272f66b59ffc923`.
6. Results and coverage are recorded in `docs/PROGRESS.md`.

The missing single comprehensive method × role × state matrix was historically documented as a nonblocking improvement. The original Phase 2 specification required role-based test coverage and denial of foreign-resource operations, not every possible combination within a single suite. CI #154 ran 479 tests (379 Surefire, 100 Failsafe) with zero failures/errors/skips. Run #43545520476 confirmed `BUILD SUCCESS`, Spotless, JaCoCo and 100 successful Failsafe tests for the pre-closure HEAD.

**Historical formal determination: Phase 2 CLOSED.** This English translation preserves the original accepted status; it is not a new independent approval or claim about the current Phase 3/4 SHA.
