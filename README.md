# SeatFlow

Event seat booking service. The interesting part is not the CRUD around events —
it is what happens when two people try to buy the same seat at the same moment.

[![CI](https://github.com/David7777k/event-booking-service/actions/workflows/ci.yml/badge.svg)](https://github.com/David7777k/event-booking-service/actions/workflows/ci.yml)

## What it does

Venues have seats. Events are scheduled at a venue and price those seats. A user
picks seats, holds them for ten minutes, and confirms. A hold that is not
confirmed releases its seats again.

## Why it exists

Booking a specific seat is a textbook race condition that a database transaction
alone does not prevent. `@Transactional` guarantees atomicity — all or nothing —
but not isolation from a concurrent writer. Under PostgreSQL's default
`READ COMMITTED` two transactions can both read a seat as available, both mark it
held, and both commit.

This service solves that explicitly, measures that the solution works, and
documents what the choice costs.

## Tech stack

| | |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4.1.1 (Spring Framework 7, Hibernate 7) |
| Database | PostgreSQL 17, Flyway migrations |
| Security | Spring Security, JWT via Nimbus |
| Tests | JUnit 5, Testcontainers, MockMvc — 93 tests |
| Build | Maven (wrapper committed) |
| Docs | OpenAPI 3 / Swagger UI |

## Architecture

Packages are organised by feature, not by layer:

```
io.github.david7777k.seatflow
├── catalog/     venues, seats, events, per-event seats
├── booking/     holds, confirmation, expiry worker
├── security/    accounts, roles, token issuing
└── common/      error handling, shared configuration
```

Each feature owns its `domain`, `repository`, `service` and `web` packages. A
top-level `controller/ service/ repository/` split breaks down as soon as a
project stops being small: understanding one feature then means opening three
distant folders. Nothing here is split into Maven modules or wrapped in ports and
adapters — that would be ceremony at this size.

## Database

Six tables. The rules this service depends on are enforced by constraints, not by
application code that reads and then writes.

```
app_user ──< booking >── event ──> venue
                            │         │
                            │         └──< seat
                            └──< event_seat >──┘
```

| Constraint | What it guarantees |
|---|---|
| `UNIQUE (event_id, seat_id)` on `event_seat` | a seat of an event is exactly one row, so it cannot hold two states |
| `EXCLUDE USING gist` on `event` | a venue cannot host overlapping events; partial, so cancelled events free their slot |
| `event_seat_booking_consistency` | a taken seat always names its booking, a free one never does |
| `UNIQUE (user_id, idempotency_key)` | partial; confirmation keys cannot collide across users |

`time_range` and `search_vector` are generated columns — derived values cannot
drift from their sources when the database derives them. The text search
configuration is named explicitly (`to_tsvector('english', …)`) because the
single-argument form depends on a session setting and is therefore not
`IMMUTABLE`, which a generated column requires.

Indexes are partial where the query is: `event_seat_available_idx` covers only
available seats, `booking_expiry_idx` only pending bookings.

## API

Browsing needs no account. Booking needs a token. Managing the catalogue needs
`ADMIN`.

| Method | Path | Access |
|---|---|---|
| `POST` | `/api/v1/auth/register` | public |
| `POST` | `/api/v1/auth/login` | public |
| `GET` | `/api/v1/events` | public — search with `q`, `venueId`, `from`, `to`, `onlyAvailable` |
| `GET` | `/api/v1/events/{id}` | public |
| `GET` | `/api/v1/events/{id}/seats` | public |
| `POST` | `/api/v1/events` | `ADMIN` |
| `POST` | `/api/v1/events/{id}/publish` | `ADMIN` |
| `POST` | `/api/v1/events/{id}/cancel` | `ADMIN` |
| `POST` | `/api/v1/venues` | `ADMIN` |
| `POST` | `/api/v1/venues/{id}/seats` | `ADMIN` |
| `GET` | `/api/v1/venues/{id}/seats` | public |
| `POST` | `/api/v1/bookings` | account holder |
| `POST` | `/api/v1/bookings/{id}/confirm` | owner only |
| `DELETE` | `/api/v1/bookings/{id}` | owner or `ADMIN` |
| `GET` | `/api/v1/bookings` | own bookings only |

Errors follow RFC 9457. Validation failures carry an `errors` map keyed by field.

| Status | Meaning here |
|---|---|
| `409` | the seat is taken, or the state transition is not allowed |
| `410` | the hold existed and has expired — start over, do not retry |
| `503` | seats are locked by someone else right now; `Retry-After` is set |
| `404` | unknown, **or** somebody else's booking |

## Authentication

Register or log in, then send the returned token as `Authorization: Bearer …`.

Signing and verification go through Nimbus rather than hand-written code —
writing your own JWT handling is the standard way to end up with a missing expiry
check or an accepted `alg: none`. Passwords are hashed with BCrypt: SHA-256 is
designed to be fast, which is exactly what an attacker with a stolen table wants.

The token subject is the user id, not the email — the id is what authorization
decisions are made against, and an email can change. Nothing secret goes in: a
JWT is signed, not encrypted, and anyone holding it can read every claim.

Login answers identically for a wrong password and an unknown address, and takes
the same time — an unknown email is compared against a dummy hash. Otherwise the
endpoint becomes a way to enumerate which addresses have accounts.

Reaching somebody else's booking answers `404`, not `403`: a `403` would confirm
that a booking with that id exists.

## Concurrency

The core problem, and the part worth reading the code for.

### The race

```
T1  BEGIN
T2  BEGIN
T1  SELECT status FROM event_seat WHERE id = 42   →  AVAILABLE
T2  SELECT status FROM event_seat WHERE id = 42   →  AVAILABLE   ← both see it free
T1  UPDATE event_seat SET status = 'HELD' WHERE id = 42
T2  UPDATE event_seat SET status = 'HELD' WHERE id = 42
T1  COMMIT
T2  COMMIT                                                        ← sold twice
```

### What measuring it actually showed

A test fires 24 simultaneous requests at the last seat, released together by a
barrier, against a real PostgreSQL instance. The result was not the expected
double sale:

| | Before the fix |
|---|---|
| Bookings created | **1** — the seat was not sold twice |
| Requests answered with a clean `409` | **0** |
| Requests that threw `ObjectOptimisticLockingFailureException` | **23** |

The `@Version` column on `EventSeat` was already preventing the double sale:
Hibernate appends `and version = ?` to each update, so the losers matched no row.
Correctness was intact. The defect was that 23 requests did all their work and
then surfaced a **500** to the caller.

### The fix, and why this one

Seats are locked before their availability is read:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select es from EventSeat es where es.id in :ids order by es.id")
List<EventSeat> lockAllByIdOrdered(Collection<Long> ids);
```

Optimistic locking is the right tool when conflicts are **rare** — it costs
nothing without contention. Selling the last seat of a popular event is the
opposite case: contention is the normal state, so the cheap path is never taken
and the expensive one is 23 transactions doing full work before being discarded.
Locking up front turns the contenders into a queue where each gets a definitive
answer on its first attempt.

The version column stays — one integer comparison, and it guards any path that
updates a seat without locking first.

Three details that are load-bearing:

- **`ORDER BY id`.** Locks are taken row by row in the order rows are returned.
  Requests for seats `{1, 2}` and `{2, 1}` would each hold one and wait for the
  other; PostgreSQL would break the deadlock by killing one.
- **No `join fetch` on the locking query.** `FOR UPDATE` applies to every table in
  the statement, so fetching the physical seat would lock rows belonging to
  unrelated events at the same venue.
- **`lock_timeout` per transaction.** A request fails in seconds instead of
  queueing behind a stuck transaction. Hitting it answers `503` with
  `Retry-After`, not `409` — nothing is wrong with the request.

### What it costs

- Contending requests **queue**; throughput on one hot seat is bounded by how
  fast the winner commits.
- It does not extend across independent databases.
- No external calls may happen inside a transaction holding seat locks, or every
  contender waits on that call too.
- Under heavy contention some callers get `503` rather than an answer —
  deliberate: a bounded failure beats an unbounded wait.

### A second approach, in the same repository

Events cannot overlap at a venue. That is the same read-then-write race one level
up, solved differently — by an exclusion constraint, so the invariant lives in the
schema and no application code can bypass it:

```sql
ALTER TABLE event ADD CONSTRAINT event_no_overlap_per_venue
    EXCLUDE USING gist (venue_id WITH =, time_range WITH &&)
    WHERE (status <> 'CANCELLED');
```

### Expiring holds

A scheduled worker releases holds that ran out, claiming batches with
`FOR UPDATE SKIP LOCKED` so several instances take disjoint work instead of
queueing behind each other.

The worker is **not** load-bearing: a booking attempt settles lapsed holds it
encounters, so a seat is never unsellable merely because the sweep has not run.
What the worker adds is coverage of seats nobody asks about.

## Testing

93 tests, all against a real PostgreSQL 17 in Testcontainers.

H2 is deliberately not used. This project depends on PostgreSQL-specific
behaviour — row locking semantics, exclusion constraints, `SKIP LOCKED`,
generated `tsvector` columns — none of which H2 reproduces. A green H2 run would
say nothing about the property the service is built around.

| Area | What is covered |
|---|---|
| Concurrency | 24 threads on one seat: exactly one success, 23 clean conflicts, zero errors |
| Constraints | each one tested for what it **rejects**, not merely that it exists |
| Hold expiry | driven by an injected `Clock`, not by sleeping |
| `SKIP LOCKED` | a second connection holds a row lock; the sweep must step over it, not block |
| Search | stemming, relevance ranking by field weight, and that the GIN index can serve the query |
| Security | forged token signature, timing-equal login failures, a stranger reaching another user's booking |

Tests isolate themselves by truncating tables rather than rolling back a
transaction — a test-managed transaction would make writes invisible to other
connections, which is exactly what the concurrency tests need to observe.

## Running it

### With Docker

```bash
cp .env.example .env
# set JWT_SECRET in .env, for example: openssl rand -base64 48
docker compose up --build
```

API on `http://localhost:8080`, Swagger UI on
`http://localhost:8080/swagger-ui.html`.

### Locally

Requires JDK 21 and a PostgreSQL 17 instance. Maven is not required — the wrapper
downloads it.

```bash
docker compose up -d postgres
export JWT_SECRET=$(openssl rand -base64 48)
./mvnw spring-boot:run
```

### Tests

```bash
./mvnw verify
```

Needs a running Docker daemon: Testcontainers starts PostgreSQL itself.

## Configuration

Every setting is an environment variable; see `.env.example`.

| Variable | Default | |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | localhost, 5432, seatflow | |
| `JWT_SECRET` | **none** | required, at least 32 characters |
| `JWT_ACCESS_TOKEN_TTL` | `PT30M` | |
| `BCRYPT_STRENGTH` | `12` | each step doubles the work |
| `SERVER_PORT` | `8080` | |

`JWT_SECRET` deliberately has no default. A service that falls back to a baked-in
key starts happily in production with a secret that is public knowledge; refusing
to start is safer.

## API documentation

Swagger UI at `/swagger-ui.html`, OpenAPI document at `/v3/api-docs`.

Authenticate with the `accessToken` from register or login, via the **Authorize**
button.

## Known limitations

This is a portfolio project. The following are absent on purpose rather than by
oversight:

- **No refresh tokens and no revocation.** An access token is valid for 30 minutes
  and cannot be killed early. Rotation with reuse detection belongs in a
  dedicated service.
- **No rate limiting.** Nothing slows password guessing beyond BCrypt's own cost.
- **No payment.** Confirming marks seats sold; no money moves.
- **No email verification or password reset.**
- **`ADMIN` is granted in the database**, never by an endpoint.
- **Several instances would run correctly** — the expiry worker uses
  `SKIP LOCKED` — but each polls independently. At scale that wants a shared
  scheduler lock.
- **Row locking does not span databases.** The approach is correct for one
  PostgreSQL instance and would need rethinking beyond that.

## Possible next steps

- Waiting list when an event sells out
- Partial cancellation of a multi-seat booking
- Outbox pattern for confirmation emails
- Load testing to put numbers on where the lock queue saturates

## License

MIT — see [LICENSE](LICENSE).
