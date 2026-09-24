# Changelog

Follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] — 2026-09-24

First complete version: the booking flow works end to end and is protected
against concurrent overbooking.

### Added

- Venue catalogue with seat maps described as sections of rows
- Events with a `DRAFT → PUBLISHED → CANCELLED` lifecycle and per-section pricing
- Full-text event search with relevance ranking, filters and pagination
- Seat holds with a ten-minute expiry, idempotent confirmation, and cancellation
- Scheduled worker releasing lapsed holds
- Registration, login, JWT authentication, and `USER` / `ADMIN` roles
- OpenAPI specification and Swagger UI
- Docker image and a compose stack running the service with PostgreSQL

### Fixed

- Concurrent requests for the same seat left all but one caller with a `500`.
  Seats are now locked before their availability is read, so losing the race
  returns a `409` naming the seat.
- A hold that ran out kept its seats unsellable until something happened to read
  that particular booking.

### Technical

- Java 21, Spring Boot 4.1.1, PostgreSQL 17, Flyway
- Invariants enforced by database constraints: a unique seat row per event, an
  exclusion constraint against overlapping events at a venue, and a check tying a
  taken seat to its booking
- Pessimistic row locking with a deterministic acquisition order and a per
  transaction `lock_timeout`
- `FOR UPDATE SKIP LOCKED` in the expiry worker so several instances take
  disjoint batches
- 93 tests against a real PostgreSQL in Testcontainers, including a 24-thread
  contention test
- GitHub Actions running the full build on every push and pull request

### Known limitations

No refresh tokens, no token revocation, no rate limiting, and no payment. See the
README for the full list.

[1.0.0]: https://github.com/David7777k/event-booking-service/releases/tag/v1.0.0
