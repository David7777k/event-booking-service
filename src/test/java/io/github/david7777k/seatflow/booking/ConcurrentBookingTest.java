package io.github.david7777k.seatflow.booking;

import io.github.david7777k.seatflow.AbstractIntegrationTest;
import io.github.david7777k.seatflow.booking.service.BookingService;
import io.github.david7777k.seatflow.booking.web.dto.CreateBookingRequest;
import io.github.david7777k.seatflow.common.error.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The test this project exists for.
 *
 * <p>Many people press "book" on the last remaining seat at the same instant.
 * Exactly one may succeed.
 *
 * <p>Two things make this a real test rather than a hopeful one. The database
 * is a real PostgreSQL instance, because the behaviour under test is its row
 * locking and H2 does not reproduce it. And the threads are released by a
 * barrier, so they contend genuinely instead of drifting apart in time and
 * quietly serialising themselves.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // Every contending request holds a connection while it waits for a row
        // lock. With the default pool of ten, most threads would block on the
        // pool rather than on the seat, and the test would be measuring the
        // wrong queue.
        "spring.datasource.hikari.maximum-pool-size=40"
})
class ConcurrentBookingTest extends AbstractIntegrationTest {

    private static final int CONTENDING_REQUESTS = 24;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BookingService bookingService;

    private long eventId;
    private long contestedSeatId;
    private List<Long> userIds;

    @BeforeEach
    void seedEventWithOneContestedSeat() throws Exception {
        long venueId = createVenueWithSingleSeat();
        eventId = createEvent(venueId);
        publish(eventId);

        contestedSeatId = jdbcTemplate.queryForObject(
                "select id from event_seat where event_id = ?", Long.class, eventId);

        userIds = jdbcTemplate.queryForList("""
                insert into app_user (email, password_hash)
                select 'buyer' || g || '@example.com', 'not-a-real-hash'
                from generate_series(1, ?) g
                returning id
                """, Long.class, CONTENDING_REQUESTS);
    }

    @Test
    void exactlyOneOfManySimultaneousRequestsGetsTheSeat() throws Exception {
        CountDownLatch ready = new CountDownLatch(CONTENDING_REQUESTS);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(CONTENDING_REQUESTS);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger failedUnexpectedly = new AtomicInteger();
        Set<String> unexpectedFailures = ConcurrentHashMap.newKeySet();

        ExecutorService pool = Executors.newFixedThreadPool(CONTENDING_REQUESTS);
        try {
            for (int i = 0; i < CONTENDING_REQUESTS; i++) {
                long userId = userIds.get(i);
                pool.submit(() -> {
                    try {
                        ready.countDown();
                        startSignal.await();

                        bookingService.hold(new CreateBookingRequest(
                                eventId, userId, List.of(contestedSeatId)));
                        succeeded.incrementAndGet();

                    } catch (ConflictException expected) {
                        // Somebody else got there first. This is the correct
                        // outcome for everyone but one thread.
                        rejected.incrementAndGet();
                    } catch (Exception unexpected) {
                        failedUnexpectedly.incrementAndGet();
                        unexpectedFailures.add(unexpected.getClass().getName());
                    } finally {
                        finished.countDown();
                    }
                });
            }

            assertThat(ready.await(20, TimeUnit.SECONDS))
                    .as("all threads should reach the barrier")
                    .isTrue();

            startSignal.countDown();

            assertThat(finished.await(60, TimeUnit.SECONDS))
                    .as("all requests should complete")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }

        // The seat is one row, so it can only ever show one status. Double
        // selling shows up as several bookings each believing it owns that row.
        Integer activeBookings = jdbcTemplate.queryForObject("""
                select count(*) from booking
                where event_id = ? and status in ('PENDING', 'CONFIRMED')
                """, Integer.class, eventId);

        assertThat(succeeded.get())
                .as("exactly one request may take the seat, %d did", succeeded.get())
                .isEqualTo(1);

        assertThat(activeBookings)
                .as("the seat must back exactly one live booking, it backs %d", activeBookings)
                .isEqualTo(1);

        assertThat(failedUnexpectedly.get())
                .as("losing the race must be a clean conflict, not an error; got: %s",
                        unexpectedFailures)
                .isZero();

        assertThat(rejected.get())
                .as("everyone who lost must be told so cleanly")
                .isEqualTo(CONTENDING_REQUESTS - 1);
    }

    /**
     * The same question one level up: the seat itself must never end up held by
     * a booking other than the one that won.
     */
    @Test
    void contestedSeatPointsAtTheBookingThatWon() throws Exception {
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(CONTENDING_REQUESTS);

        ExecutorService pool = Executors.newFixedThreadPool(CONTENDING_REQUESTS);
        try {
            for (int i = 0; i < CONTENDING_REQUESTS; i++) {
                long userId = userIds.get(i);
                pool.submit(() -> {
                    try {
                        startSignal.await();
                        bookingService.hold(new CreateBookingRequest(
                                eventId, userId, List.of(contestedSeatId)));
                    } catch (Exception ignored) {
                        // outcome per thread is asserted by the test above
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startSignal.countDown();
            assertThat(finished.await(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        Integer mismatched = jdbcTemplate.queryForObject("""
                select count(*)
                from event_seat es
                join booking b on b.id = es.booking_id
                where es.id = ?
                  and b.status not in ('PENDING', 'CONFIRMED')
                """, Integer.class, contestedSeatId);

        assertThat(mismatched)
                .as("the held seat must reference a live booking")
                .isZero();

        String status = jdbcTemplate.queryForObject(
                "select status from event_seat where id = ?", String.class, contestedSeatId);
        assertThat(status).isEqualTo("HELD");
    }

    // --- helpers --------------------------------------------------------------

    private long createVenueWithSingleSeat() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/venues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Tiny Hall", "address": "Kyiv"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        long venueId = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));

        mockMvc.perform(post("/api/v1/venues/{id}/seats", venueId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sections": [{"name": "A", "rows": ["1"], "seatsPerRow": 1}]}
                                """))
                .andExpect(status().isCreated());

        return venueId;
    }

    private long createEvent(long venueId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"venueId": %d, "title": "Sold Out Show", "description": "One seat only",
                                 "startsAt": "2026-11-01T18:00:00Z", "endsAt": "2026-11-01T21:00:00Z",
                                 "salesStartAt": "2020-01-01T00:00:00Z",
                                 "salesEndAt": "2030-01-01T00:00:00Z",
                                 "defaultPrice": 500}
                                """.formatted(venueId)))
                .andExpect(status().isCreated())
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    private void publish(long id) throws Exception {
        mockMvc.perform(post("/api/v1/events/{id}/publish", id)).andExpect(status().isOk());
    }
}
