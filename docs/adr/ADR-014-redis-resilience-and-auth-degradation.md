# ADR-014 — Redis outage resilience and authentication degradation

- **Date:** 2026-11-10
- **Status:** ACCEPTED
- **Phase:** 4

## Context

Redis has three separate risk profiles in the initial phase:

1. Ticket-type catalog metadata cache.
2. Login attempt rate limiting.
3. Refresh-token persistence and revocation.

A Redis outage should not turn an optional read cache into an API outage, nor should it silently accept authentication sessions whose revocation cannot be checked. Previously issued JWT access tokens should remain locally verifiable.

Runtime `application.yml` sets `spring.data.redis.timeout=500ms`; the isolated `RedisOutageIT` context additionally sets `spring.data.redis.connect-timeout=500ms`. The Redis outage integration class uses its **own** Redis container and pauses it to simulate a stalled service.

## Alternatives considered

1. **Fail closed everywhere**: consistent but makes catalog and login unavailable during Redis outages.
2. **Fail open everywhere**: keeps availability but risks accepting refresh sessions without checking/persisting revocation.
3. **Risk-specific degradation**: fall back for cached reads and temporarily bypass rate limiting, while failing closed for refresh-token state.

## Decision: differentiated resilience

### Catalog cache — fail-open fallback to PostgreSQL

- Redis holds only serializable metadata, never current inventory; PostgreSQL supplies stock availability.
- `CacheErrorHandler` logs and bypasses Redis **access and serialization** failures, allowing the business read to continue.
- Failed invalidations are **not retried**. An existing cache entry may stay stale until its TTL expires.
- Runtime TTL `TICKET_TYPES_CACHE_TTL` defaults to five minutes. `RedisOutageIT` sets class-wide `ticketing.cache.ticket-types.ttl=20s` for **all** tests in that class, not just the invalidation test.
- `should_serveStaleCatalogUntilTtlAfterFailedInvalidation` updates PostgreSQL while Redis is paused, then shows that the earlier cache metadata remains until TTL and is refreshed afterward.

### Login rate limiting — fail open

- On Redis `DataAccessException`, allow the login request to proceed while logging the degraded rate-limit state.
- This prioritizes service continuity over temporary brute-force resistance.
- **Fail-open rate limiting is not fail-open sessions**: a valid login still fails HTTP 503 if it cannot store its refresh token in Redis. Invalid credentials still fail normally.

### Refresh tokens — fail closed (HTTP 503)

- Store, inspect and revoke refresh tokens through Redis.
- Redis failures become `TokenStoreUnavailableException`, mapped to HTTP 503.
- Refresh/logout are not successful when revocation cannot be checked or written.
- A successful-credential login may return 503 if the refresh token cannot be stored.
- `should_failClosedForAuthenticationSessionsDuringRedisOutage` covers refresh, login, logout and later recovery.

### Access JWT — locally validated, independent of Redis

- Spring Security Resource Server validates JWT signature, expiration and token type locally.
- Verifying an access token does not consult Redis refresh-token storage.
- `should_acceptValidAccessTokenDuringRedisOutage` logs in beforehand and verifies a protected access-token request during a paused Redis container.

### Timeouts and measured latency

- Runtime Redis command timeout is 500 ms; isolated outage tests also configure a 500 ms connection timeout. `should_configureShortCommandTimeoutOnLettuceClient` checks effective Lettuce timeout.
- Measured degradation examples: approximately **1,014 ms** for catalog fallback and **1,023 ms** for a public event GET. Each performs a failed ~500 ms Redis get and ~500 ms Redis put/refresh, plus processing overhead.
- These figures describe the tested outage environment; fallback preserves behavior but does not remove waiting latency.
- An HTTP 503 caused by a timeout **does not prove that Redis never executed the command**. A queued command might take effect after Redis resumes.

### Transactional authority

- PostgreSQL remains authoritative for inventory, reservations and orders.
- Order item unit price is stored as an immutable snapshot. Redis cannot set inventory or charged price.

## Historical CI evidence

The historical **CI #107** on code commit `24d6a514f1f8a4d467b1c457d12a478e24f75c36` passed **313 unit** and **57 integration** tests with no failures, errors or skips. Its `RedisOutageIT` ran seven cases testing the actual Lettuce timeout, dedicated Redis container, catalog fallback, stale TTL behavior, fail-closed refresh sessions and locally verifiable access tokens.

Later complete CI runs also exercised the outage suite; review them by exact SHA before asserting the outcome of newly edited code.

## Consequences and limitations

- Catalog remains available during Redis outage but failed invalidation can leave metadata stale up to the remaining TTL.
- Command timeout is per Redis operation; requests involving multiple cache calls accumulate the delays.
- Login rate limiting temporarily stops during outages, increasing brute-force exposure for that interval.
- Redis-dependent refresh/session operations return HTTP 503; existing signed access tokens remain valid until expiry.
- Only documented Redis faults should be bypassed; unrelated application failures must not be swallowed.
- Redis recovery does not automatically rebuild every stale entry; TTL allows eventual refresh.

## Historical deferred scope — later superseded by ADR-020

**At the time of ADR-014**, three items from the original specification were deliberately deferred, not presented as implemented:

1. **`GET /events/{id}` public event metadata cache:** originally deferred pending cross-entity invalidation and consistency rules.
2. **Redis `reservation:{id}` keys:** originally deferred to avoid making Redis a second source of truth for reservation state.
3. **Rate limiting for reservations/orders:** originally deferred pending exact operation-specific scopes, thresholds and responses.

**These historical deferrals were subsequently addressed and accepted through [ADR-020](ADR-020-phase-4-redis-completion.md).** The current code implements public event metadata cache, reservation projections after commit and rate limits for reservations/orders while PostgreSQL remains authoritative. Do not mark these historical deferrals as active omissions without inspecting ADR-020.
