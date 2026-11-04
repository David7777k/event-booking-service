# SeatFlow

REST API for reserving seats at events. Users can hold seats for ten minutes, confirm a booking, or cancel it. Expired holds release the seats.

Java 21 · Spring Boot · PostgreSQL 17 · Flyway · Spring Security · JWT · Testcontainers

[![CI](https://github.com/David7777k/event-booking-service/actions/workflows/ci.yml/badge.svg)](https://github.com/David7777k/event-booking-service/actions/workflows/ci.yml)

## Main decisions

- **Concurrent booking:** seat rows are locked before availability is checked. Locks are requested in ID order, and a transaction-level timeout bounds the wait.
- **Database constraints:** PostgreSQL prevents overlapping events at a venue and inconsistent seat/booking states.
- **Hold expiry:** a scheduled worker uses `FOR UPDATE SKIP LOCKED` to claim batches. Booking operations also settle expired holds.
- **Confirmation retries:** an idempotency key allows a client to repeat a successful confirmation. Booking row locks serialize state changes against concurrent requests and the expiry worker. Expired confirmation commits the release before returning `410`.
- **Access control:** JWT authentication, user-owned bookings, and a separate admin role for catalogue management.

The concurrency test starts 24 competing service calls for one seat. It asserts one successful booking, 23 conflicts, and no unexpected exceptions. This checks contention on a single seat; it is not a throughput benchmark.

## Code to read

| Area | Entry point |
|---|---|
| Booking lifecycle and transaction boundaries | [BookingService](src/main/java/io/github/david7777k/seatflow/booking/service/BookingService.java) |
| Seat locking | [EventSeatRepository](src/main/java/io/github/david7777k/seatflow/catalog/repository/EventSeatRepository.java) |
| Concurrent booking test | [ConcurrentBookingTest](src/test/java/io/github/david7777k/seatflow/booking/ConcurrentBookingTest.java) |
| Constraints and indexes | [Schema migration](src/main/resources/db/migration/V1__create_core_schema.sql) |
| Expiry processing | [HoldExpiryService](src/main/java/io/github/david7777k/seatflow/booking/service/HoldExpiryService.java) |

Code is grouped by feature: `catalog`, `booking`, `security`, and `common`. Each feature contains its own persistence, service, and HTTP code.

## Run

With Docker running:

```bash
cp .env.example .env
# Set JWT_SECRET in .env to a random secret of at least 32 characters.
docker compose up --build
```

- API: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI: http://localhost:8080/v3/api-docs

For local development, install JDK 21, start PostgreSQL with `docker compose up -d postgres`, set `JWT_SECRET` in the shell, and run `./mvnw spring-boot:run`. On Windows use `.\mvnw.cmd` instead of `./mvnw`.

## API overview

| Action | Endpoint |
|---|---|
| Register / log in | `POST /api/v1/auth/register`, `POST /api/v1/auth/login` |
| Browse / search events | `GET /api/v1/events` |
| View event seats | `GET /api/v1/events/{id}/seats` |
| Hold seats | `POST /api/v1/bookings` |
| Confirm | `POST /api/v1/bookings/{id}/confirm` |
| Cancel | `DELETE /api/v1/bookings/{id}` |
| List own bookings | `GET /api/v1/bookings` |

Send the access token as `Authorization: Bearer <token>`. Venue and event management require `ADMIN`, assigned in the database. Swagger lists request fields and the remaining endpoints.

Errors use Problem Details responses. Seat conflicts return `409`, expired holds return `410`, and lock timeouts return `503` with `Retry-After`. Access to another user's booking returns `404`.

## Verification

```bash
./mvnw verify
```

Tests require Docker. Integration tests use PostgreSQL through Testcontainers to exercise row locks, exclusion constraints, full-text search, hold expiry, and access control. Time-dependent expiry tests use an injected `Clock`. Regression cases include simultaneous confirmation retries and seat release at the exact hold deadline.

## Tradeoffs and limits

Locks serialize requests for the same seats. Their scope is one database, and slow work inside a locking transaction increases waiting time.

This is a backend portfolio project, without payment processing, email verification, password reset, refresh tokens, token revocation, or rate limiting. A confirmed booking records the seat allocation; it does not charge a customer. Production deployment would need those controls and load testing.

## License

[MIT](LICENSE)
