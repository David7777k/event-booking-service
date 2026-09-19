package io.github.david7777k.seatflow.booking;

import io.github.david7777k.seatflow.AbstractIntegrationTest;
import io.github.david7777k.seatflow.booking.service.HoldExpiryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Import(HoldExpiryServiceTest.FixedClockConfiguration.class)
@TestPropertySource(properties = {
        // The scheduler is switched off so it cannot expire rows behind the
        // back of a test that is asserting what one explicit call does.
        "seatflow.booking.expiry-sweep.enabled=false"
})
class HoldExpiryServiceTest extends AbstractIntegrationTest {

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
    private HoldExpiryService holdExpiryService;

    @Autowired
    private DataSource dataSource;

    private long eventId;
    private long userId;
    private List<Long> eventSeatIds;

    @BeforeEach
    void seedEventAndUser() {
        CLOCK.set(Instant.parse("2026-10-15T12:00:00Z"));

        Long venueId = jdbcTemplate.queryForObject("""
                insert into venue (name, address) values ('Hall', 'Kyiv') returning id
                """, Long.class);

        jdbcTemplate.update("""
                insert into seat (venue_id, section, row_label, seat_number)
                select ?, 'A', '1', g from generate_series(1, 10) g
                """, venueId);

        eventId = jdbcTemplate.queryForObject("""
                insert into event (venue_id, title, description,
                                   starts_at, ends_at, sales_start_at, sales_end_at, status)
                values (?, 'Show', 'Live music',
                        timestamptz '2026-11-01 18:00:00+00', timestamptz '2026-11-01 21:00:00+00',
                        timestamptz '2026-10-01 00:00:00+00', timestamptz '2026-10-31 00:00:00+00',
                        'PUBLISHED')
                returning id
                """, Long.class, venueId);

        jdbcTemplate.update("""
                insert into event_seat (event_id, seat_id, price)
                select ?, id, 500 from seat where venue_id = ?
                """, eventId, venueId);

        userId = jdbcTemplate.queryForObject("""
                insert into app_user (email, password_hash)
                values ('buyer@example.com', 'not-a-real-hash') returning id
                """, Long.class);

        eventSeatIds = jdbcTemplate.queryForList(
                "select id from event_seat where event_id = ? order by id", Long.class, eventId);
    }

    // --- what it expires -----------------------------------------------------

    @Test
    void expiresLapsedHoldAndReturnsItsSeats() {
        long booking = hold(Instant.parse("2026-10-15T11:50:00Z"), eventSeatIds.get(0), eventSeatIds.get(1));

        int expired = holdExpiryService.expireBatch(100);

        assertThat(expired).isEqualTo(1);
        assertThat(bookingStatus(booking)).isEqualTo("EXPIRED");
        assertThat(seatStatus(eventSeatIds.get(0))).isEqualTo("AVAILABLE");
        assertThat(seatStatus(eventSeatIds.get(1))).isEqualTo("AVAILABLE");
        assertThat(seatBookingId(eventSeatIds.get(0))).isNull();
    }

    @Test
    void leavesHoldThatIsStillValid() {
        long booking = hold(Instant.parse("2026-10-15T12:05:00Z"), eventSeatIds.get(0));

        assertThat(holdExpiryService.expireBatch(100)).isZero();
        assertThat(bookingStatus(booking)).isEqualTo("PENDING");
        assertThat(seatStatus(eventSeatIds.get(0))).isEqualTo("HELD");
    }

    @Test
    void leavesConfirmedBookingAlone() {
        long booking = hold(Instant.parse("2026-10-15T11:50:00Z"), eventSeatIds.get(0));
        jdbcTemplate.update("""
                update booking set status = 'CONFIRMED', confirmed_at = now(), expires_at = null
                where id = ?
                """, booking);
        jdbcTemplate.update("update event_seat set status = 'BOOKED' where booking_id = ?", booking);

        assertThat(holdExpiryService.expireBatch(100)).isZero();
        assertThat(bookingStatus(booking)).isEqualTo("CONFIRMED");
        assertThat(seatStatus(eventSeatIds.get(0))).isEqualTo("BOOKED");
    }

    @Test
    void expiresHoldOnlyAfterItsDeadlinePasses() {
        long booking = hold(Instant.parse("2026-10-15T12:10:00Z"), eventSeatIds.get(0));

        assertThat(holdExpiryService.expireBatch(100)).isZero();

        CLOCK.advance(Duration.ofMinutes(11));

        assertThat(holdExpiryService.expireBatch(100)).isEqualTo(1);
        assertThat(bookingStatus(booking)).isEqualTo("EXPIRED");
    }

    // --- batching -------------------------------------------------------------

    @Test
    void honoursBatchSize() {
        for (int i = 0; i < 5; i++) {
            hold(Instant.parse("2026-10-15T11:50:00Z"), eventSeatIds.get(i));
        }

        assertThat(holdExpiryService.expireBatch(2)).isEqualTo(2);
        assertThat(holdExpiryService.expireBatch(2)).isEqualTo(2);
        assertThat(holdExpiryService.expireBatch(2)).isEqualTo(1);
        assertThat(holdExpiryService.expireBatch(2)).isZero();
    }

    @Test
    void releasesOldestLapsedHoldsFirst() {
        long older = hold(Instant.parse("2026-10-15T11:00:00Z"), eventSeatIds.get(0));
        long newer = hold(Instant.parse("2026-10-15T11:55:00Z"), eventSeatIds.get(1));

        assertThat(holdExpiryService.expireBatch(1)).isEqualTo(1);

        assertThat(bookingStatus(older)).isEqualTo("EXPIRED");
        assertThat(bookingStatus(newer)).isEqualTo("PENDING");
    }

    // --- the part that makes several workers safe -----------------------------

    @Test
    void skipsHoldsAnotherWorkerIsAlreadyHolding() throws Exception {
        long claimedByOther = hold(Instant.parse("2026-10-15T11:00:00Z"), eventSeatIds.get(0));
        long free = hold(Instant.parse("2026-10-15T11:55:00Z"), eventSeatIds.get(1));

        // Stand in for a second worker: take a row lock on one booking and hold
        // it open across the sweep.
        try (Connection otherWorker = dataSource.getConnection()) {
            otherWorker.setAutoCommit(false);
            try (PreparedStatement lock =
                         otherWorker.prepareStatement("select id from booking where id = ? for update")) {
                lock.setLong(1, claimedByOther);
                lock.executeQuery();
            }

            // Without SKIP LOCKED this call would block on that row until the
            // other connection committed. Instead it steps over it.
            int expired = holdExpiryService.expireBatch(100);

            assertThat(expired)
                    .as("the locked hold must be skipped, not waited on")
                    .isEqualTo(1);

            otherWorker.rollback();
        }

        assertThat(bookingStatus(free)).isEqualTo("EXPIRED");
        assertThat(bookingStatus(claimedByOther))
                .as("the skipped hold stays untouched")
                .isEqualTo("PENDING");

        // Once the other worker is gone, the next sweep picks it up.
        assertThat(holdExpiryService.expireBatch(100)).isEqualTo(1);
        assertThat(bookingStatus(claimedByOther)).isEqualTo("EXPIRED");
    }

    // --- helpers --------------------------------------------------------------

    private long hold(Instant expiresAt, Long... seats) {
        Long bookingId = jdbcTemplate.queryForObject("""
                insert into booking (event_id, user_id, status, total_amount, expires_at)
                values (?, ?, 'PENDING', ?, ?)
                returning id
                """, Long.class, eventId, userId,
                BigDecimal.valueOf(500L * seats.length), java.sql.Timestamp.from(expiresAt));

        for (Long seat : seats) {
            jdbcTemplate.update(
                    "update event_seat set status = 'HELD', booking_id = ? where id = ?", bookingId, seat);
        }
        return bookingId;
    }

    private String bookingStatus(long bookingId) {
        return jdbcTemplate.queryForObject(
                "select status from booking where id = ?", String.class, bookingId);
    }

    private String seatStatus(long eventSeatId) {
        return jdbcTemplate.queryForObject(
                "select status from event_seat where id = ?", String.class, eventSeatId);
    }

    private Long seatBookingId(long eventSeatId) {
        return jdbcTemplate.queryForObject(
                "select booking_id from event_seat where id = ?", Long.class, eventSeatId);
    }

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
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
