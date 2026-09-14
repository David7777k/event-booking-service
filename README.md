# SeatFlow

Event seat booking service. The interesting part is not the CRUD around events —
it is what happens when two people try to buy the same seat at the same moment.

## Status

Early development. See [CHANGELOG.md](CHANGELOG.md) for released versions and the
[open issues](../../issues) for what is being built next.

## Why this project exists

Booking a specific seat is a textbook example of a race condition that a database
transaction alone does not prevent. `@Transactional` guarantees atomicity — all or
nothing — but not isolation from a concurrent writer. Under the default
`READ COMMITTED` level two transactions can both read a seat as available, both
mark it as held, and both commit. The seat is then sold twice.

This service solves that explicitly rather than accidentally, and documents the
trade-offs of the approach it takes.

## Tech stack

| | |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4.1.1 (Spring Framework 7) |
| Build | Maven (via wrapper) |
| Tests | JUnit 5, Spring Boot Test |

PostgreSQL, Flyway, Spring Security, Testcontainers and Docker are added in later
milestones as the features that need them land.

## Running locally

Requires JDK 21. Maven is not required — the wrapper downloads it.

```bash
./mvnw spring-boot:run
```

The application starts on `http://localhost:8080`.

## Running the tests

```bash
./mvnw verify
```

## License

MIT — see [LICENSE](LICENSE).
