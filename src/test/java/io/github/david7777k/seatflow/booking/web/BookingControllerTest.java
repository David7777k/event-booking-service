package io.github.david7777k.seatflow.booking.web;

import io.github.david7777k.seatflow.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import(BookingControllerTest.FixedClockConfiguration.class)
class BookingControllerTest extends AbstractIntegrationTest {

    /**
     * Time is controlled rather than waited on. Expiry is a ten-minute rule;
     * testing it by sleeping would make the suite ten minutes slower and still
     * flaky. The clock is mutable so a test can move "now" forward.
     */
    static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-15T12:00:00Z"));

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    private long eventId;
    private long userId;
    private List<Long> seatIds;

    @BeforeEach
    void seedPublishedEvent() throws Exception {
        CLOCK.set(Instant.parse("2026-10-15T12:00:00Z"));

        long venueId = createVenue();
        eventId = createEvent(venueId);
        publish(eventId);

        userId = jdbcTemplate.queryForObject("""
                insert into app_user (email, password_hash)
                values ('buyer@example.com', 'not-a-real-hash')
                returning id
                """, Long.class);

        seatIds = jdbcTemplate.queryForList(
                "select id from event_seat where event_id = ? order by id", Long.class, eventId);
    }

    // --- holding --------------------------------------------------------------

    @Test
    void holdsSeatsAndReportsWhenTheHoldLapses() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(seatIds.get(0), seatIds.get(1))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(1000.00))
                .andExpect(jsonPath("$.seats", hasSize(2)))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-15T12:10:00Z"));

        assertThat(seatStatus(seatIds.get(0))).isEqualTo("HELD");
    }

    @Test
    void refusesSeatAlreadyHeldBySomeoneElse() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(seatIds.get(0))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(seatIds.get(0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Seat unavailable"))
                .andExpect(jsonPath("$.seatId").value(seatIds.get(0)));
    }

    @Test
    void refusesToBookUnpublishedEvent() throws Exception {
        long venueId = createVenue();
        long draft = createEvent(venueId);
        List<Long> draftSeats = jdbcTemplate.queryForList(
                "select id from event_seat where event_id = ? order by id", Long.class, draft);

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId": %d, "userId": %d, "seatIds": [%d]}
                                """.formatted(draft, userId, draftSeats.get(0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Event %d is not on sale".formatted(draft)));
    }

    @Test
    void refusesToBookBeforeSalesOpen() throws Exception {
        CLOCK.set(Instant.parse("2026-09-01T12:00:00Z"));

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(seatIds.get(0))))
                .andExpect(status().isConflict());
    }

    @Test
    void refusesSeatsBelongingToAnotherEvent() throws Exception {
        long otherVenue = createVenue();
        long otherEvent = createEvent(otherVenue);
        Long foreignSeat = jdbcTemplate.queryForObject(
                "select min(id) from event_seat where event_id = ?", Long.class, otherEvent);

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId": %d, "userId": %d, "seatIds": [%d, %d]}
                                """.formatted(eventId, userId, seatIds.get(0), foreignSeat)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("All seats must belong to event %d".formatted(eventId)));
    }

    @Test
    void returnsNotFoundForUnknownSeat() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId": %d, "userId": %d, "seatIds": [999999]}
                                """.formatted(eventId, userId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsEmptySeatSelection() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId": %d, "userId": %d, "seatIds": []}
                                """.formatted(eventId, userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.seatIds").value("at least one seat is required"));
    }

    // --- confirming -----------------------------------------------------------

    @Test
    void confirmsHeldBooking() throws Exception {
        long bookingId = hold(seatIds.get(0), seatIds.get(1));

        mockMvc.perform(post("/api/v1/bookings/{id}/confirm", bookingId)
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedAt").value("2026-10-15T12:00:00Z"))
                .andExpect(jsonPath("$.expiresAt").doesNotExist());

        assertThat(seatStatus(seatIds.get(0))).isEqualTo("BOOKED");
    }

    @Test
    void replayingConfirmationWithSameKeyReturnsSameBooking() throws Exception {
        long bookingId = hold(seatIds.get(0));

        mockMvc.perform(post("/api/v1/bookings/{id}/confirm", bookingId)
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isOk());

        // the client timed out and retried with the same key
        mockMvc.perform(post("/api/v1/bookings/{id}/confirm", bookingId)
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.id").value(bookingId));
    }

    @Test
    void refusesSecondConfirmationWithDifferentKey() throws Exception {
        long bookingId = hold(seatIds.get(0));

        mockMvc.perform(post("/api/v1/bookings/{id}/confirm", bookingId)
                        .header("Idempotency-Key", "key-1"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/bookings/{id}/confirm", bookingId)
                        .header("Idempotency-Key", "key-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("Booking %d is already confirmed".formatted(bookingId)));
    }

    @Test
    void refusesToConfirmAfterHoldLapses() throws Exception {
        long bookingId = hold(seatIds.get(0));

        CLOCK.advance(Duration.ofMinutes(11));

        mockMvc.perform(post("/api/v1/bookings/{id}/confirm", bookingId))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.title").value("Hold expired"));
    }

    @Test
    void releasesSeatsWhenHoldLapses() throws Exception {
        long bookingId = hold(seatIds.get(0));
        assertThat(seatStatus(seatIds.get(0))).isEqualTo("HELD");

        CLOCK.advance(Duration.ofMinutes(11));

        mockMvc.perform(get("/api/v1/bookings/{id}", bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));

        assertThat(seatStatus(seatIds.get(0))).isEqualTo("AVAILABLE");
    }

    @Test
    void seatFreedByLapsedHoldCanBeBookedAgain() throws Exception {
        hold(seatIds.get(0));
        CLOCK.advance(Duration.ofMinutes(11));

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(seatIds.get(0))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    // --- cancelling -----------------------------------------------------------

    @Test
    void cancellingReleasesSeats() throws Exception {
        long bookingId = hold(seatIds.get(0), seatIds.get(1));

        mockMvc.perform(delete("/api/v1/bookings/{id}", bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(seatStatus(seatIds.get(0))).isEqualTo("AVAILABLE");
        assertThat(seatStatus(seatIds.get(1))).isEqualTo("AVAILABLE");
    }

    @Test
    void refusesToCancelConfirmedBooking() throws Exception {
        long bookingId = hold(seatIds.get(0));
        mockMvc.perform(post("/api/v1/bookings/{id}/confirm", bookingId)).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/bookings/{id}", bookingId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("Booking %d cannot move from CONFIRMED to CANCELLED".formatted(bookingId)));
    }

    // --- reading --------------------------------------------------------------

    @Test
    void listsBookingsOfOneUser() throws Exception {
        hold(seatIds.get(0));
        hold(seatIds.get(1));

        mockMvc.perform(get("/api/v1/bookings").param("userId", String.valueOf(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)));
    }

    @Test
    void returnsNotFoundForUnknownBooking() throws Exception {
        mockMvc.perform(get("/api/v1/bookings/999999"))
                .andExpect(status().isNotFound());
    }

    // --- helpers --------------------------------------------------------------

    private String bookingJson(Long... seats) {
        String ids = String.join(", ", java.util.Arrays.stream(seats).map(String::valueOf).toList());
        return """
                {"eventId": %d, "userId": %d, "seatIds": [%s]}
                """.formatted(eventId, userId, ids);
    }

    private long hold(Long... seats) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson(seats)))
                .andExpect(status().isCreated())
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    private String seatStatus(Long eventSeatId) {
        return jdbcTemplate.queryForObject(
                "select status from event_seat where id = ?", String.class, eventSeatId);
    }

    private long createVenue() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/venues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Hall %d", "address": "Kyiv"}
                                """.formatted(System.nanoTime())))
                .andExpect(status().isCreated())
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        long venueId = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));

        mockMvc.perform(post("/api/v1/venues/{id}/seats", venueId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sections": [{"name": "A", "rows": ["1"], "seatsPerRow": 5}]}
                                """))
                .andExpect(status().isCreated());

        return venueId;
    }

    private long createEvent(long venueId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"venueId": %d, "title": "Concert", "description": "Live music",
                                 "startsAt": "2026-11-01T18:00:00Z", "endsAt": "2026-11-01T21:00:00Z",
                                 "salesStartAt": "2026-10-01T00:00:00Z",
                                 "salesEndAt": "2026-10-31T00:00:00Z",
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

    /** A clock a test can move. */
    static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant moment) {
            this.now = moment;
        }

        void advance(Duration amount) {
            this.now = now.plus(amount);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
