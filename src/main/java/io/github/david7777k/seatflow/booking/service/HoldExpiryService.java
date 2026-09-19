package io.github.david7777k.seatflow.booking.service;

import io.github.david7777k.seatflow.booking.domain.Booking;
import io.github.david7777k.seatflow.booking.repository.BookingRepository;
import io.github.david7777k.seatflow.catalog.domain.EventSeat;
import io.github.david7777k.seatflow.catalog.repository.EventSeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Releases holds that ran out.
 *
 * <p>This is a tidy-up, not a guarantee. Correctness does not depend on it:
 * {@link BookingService} settles lapsed holds it encounters, so a seat is never
 * unsellable merely because this has not run. What the worker adds is that a
 * seat nobody asks about still returns to the catalogue, instead of waiting for
 * a request that may never come.
 */
@Service
public class HoldExpiryService {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryService.class);

    private final BookingRepository bookingRepository;
    private final EventSeatRepository eventSeatRepository;
    private final Clock clock;

    public HoldExpiryService(BookingRepository bookingRepository,
                             EventSeatRepository eventSeatRepository,
                             Clock clock) {
        this.bookingRepository = bookingRepository;
        this.eventSeatRepository = eventSeatRepository;
        this.clock = clock;
    }

    /**
     * Expires one batch of lapsed holds.
     *
     * <p>{@code REQUIRES_NEW} so each batch commits on its own. A single
     * transaction spanning every batch would hold locks for the whole sweep and
     * lose all its work if the last batch failed.
     *
     * @return how many holds were expired, so the caller can tell whether to
     *         ask for another batch
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int expireBatch(int batchSize) {
        Instant now = clock.instant();

        List<Long> claimed = bookingRepository.claimLapsedHoldIds(now, batchSize);
        if (claimed.isEmpty()) {
            return 0;
        }

        List<Booking> bookings = bookingRepository.findByIdIn(claimed);
        int releasedSeats = 0;

        for (Booking booking : bookings) {
            List<EventSeat> seats = eventSeatRepository.findByBookingId(booking.getId());
            seats.forEach(EventSeat::release);
            booking.expire();
            releasedSeats += seats.size();
        }

        log.info("Expired {} lapsed holds, released {} seats", bookings.size(), releasedSeats);

        return bookings.size();
    }
}
