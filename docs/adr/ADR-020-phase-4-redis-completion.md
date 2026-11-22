# ADR-020 — Completion of the Phase 4 Redis scope

- **Status:** ACCEPTED
- **Date:** 2026-11-13
- **Phase:** 4
- **Relation:** Supersedes all three historically deferred items in ADR-014, preserving its risk-specific outage degradation.

## Context

ADR-014 initially deferred public event caching, reservation projections (`reservation:{id}` keys) and rate limiting for reservation/order creation. Those deferrals must **not** be misrepresented as completed Phase 4 acceptance.

## Decision

### Public event metadata cache

- Cache metadata of public `GET /events/{id}` detail and the most popular first public listing, using a `ticketing:` prefix and 60-second TTL.
- **Do not cache** `availableQuantity`. Every response combines cached metadata with fresh availability from PostgreSQL.
- Invalidate event detail/listings when events are created, edited, published or cancelled, and when their ticket types are changed.
- Filtered and paginated lists outside the common first-page case may read PostgreSQL directly to avoid unbounded cache keys.

### Temporary reservation projection

- Write `ticketing:reservation:{id}` with minimal `status`, `expiresAt` data and TTL equal to remaining reservation time.
- Create only **after the PostgreSQL commit**; delete/update on cancellation, expiration or confirmation. Redis errors are logged and **must not roll back** the PostgreSQL transaction.
- The key is a read/notification projection, never authority for stock, confirmation or authorization. Missing keys or outages must fall back to PostgreSQL.

### Rate limits

- Login: 5 attempts per 60 seconds, scoped by IP + email.
- Reservations: 20 creations per 60 seconds per user.
- Orders: 10 creations per 60 seconds per user.
- Use fixed-window atomic key expiry. Exceeding limits produces HTTP 429.
- On Redis outage these rate limits are **fail open**; PostgreSQL still protects inventory and order idempotency. The temporary brute-force exposure is documented in ADR-014.

## Consequences

- Simulate Redis outage with a paused container, and test cache hits, expiration and invalidation against actual Redis.
- No commercial database write depends on Redis projection completion.
- Refresh-token operations remain **fail closed**, while JWT access-token signature/expiry checks are local.

## Implementation and evidence

- `EventCatalogCacheIT` tests TTL, inventory exclusion, after-commit invalidation for events/ticket types and venue changes through application events.
- `ReservationRedisProjectionService` writes after commit with remaining TTL and removes projection when resolved; `ReservationRedisProjectionIT` tests commit, TTL, cancellation, confirmation and expiration.
- `RedisRateLimiterIT` verifies separate reservation/order thresholds, window TTL and counter reset; controllers enforce before resource creation.
- Redis outage cannot roll back a PostgreSQL inventory transaction; session refresh continues to enforce fail-closed ADR-014.
- Historical **CI #286**, SHA `65c6abf13af7eb8c1195f41c0e25f9d9e5cd2277`, ran 390 unit and 147 `ticketing-api` integration tests, no failures/errors/skips; `EventCatalogCacheIT` 5/5, `RedisRateLimiterIT` 3/3 and `ReservationRedisProjectionIT` 5/5; Spotless and JaCoCo passed, BUILD SUCCESS.

This evidence addresses H81's technical implementation. **It is not equivalent to a formal Phase 4 approval.**
