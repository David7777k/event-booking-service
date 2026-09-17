package io.github.david7777k.seatflow.booking.service;

import io.github.david7777k.seatflow.booking.domain.Booking;
import io.github.david7777k.seatflow.booking.domain.BookingStatus;
import io.github.david7777k.seatflow.booking.domain.HoldExpiredException;
import io.github.david7777k.seatflow.booking.repository.BookingRepository;
import io.github.david7777k.seatflow.booking.web.dto.BookingResponse;
import io.github.david7777k.seatflow.booking.web.dto.CreateBookingRequest;
import io.github.david7777k.seatflow.catalog.domain.Event;
import io.github.david7777k.seatflow.catalog.domain.EventSeat;
import io.github.david7777k.seatflow.catalog.domain.SeatUnavailableException;
import io.github.david7777k.seatflow.catalog.repository.EventRepository;
import io.github.david7777k.seatflow.catalog.repository.EventSeatRepository;
import io.github.david7777k.seatflow.common.error.ConflictException;
import io.github.david7777k.seatflow.common.error.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookingRepository;
    private final EventRepository eventRepository;
    private final EventSeatRepository eventSeatRepository;
    private final Clock clock;
    private final Duration holdDuration;

    public BookingService(BookingRepository bookingRepository,
                          EventRepository eventRepository,
                          EventSeatRepository eventSeatRepository,
                          Clock clock,
                          @Value("${seatflow.booking.hold-duration:PT10M}") Duration holdDuration) {
        this.bookingRepository = bookingRepository;
        this.eventRepository = eventRepository;
        this.eventSeatRepository = eventSeatRepository;
        this.clock = clock;
        this.holdDuration = holdDuration;
    }

    /**
     * Holds the requested seats for a limited time.
     *
     * <h4>This method has a known race condition</h4>
     *
     * The availability check below reads each seat and then writes it. Two
     * requests for the same seat can both read it as AVAILABLE before either
     * writes, and both will succeed - the seat is sold twice. {@code
     * @Transactional} does not prevent this: it gives atomicity, not isolation
     * from a concurrent writer, and under PostgreSQL's default READ COMMITTED
     * both transactions see a consistent snapshot that simply predates the
     * other's write.
     *
     * <p>This is deliberate. Issue #7 reproduces the race with a concurrent
     * test and then closes it, so the repository records the problem and the
     * fix as separate, reviewable steps rather than presenting the answer as if
     * it had been obvious.
     */
    @Transactional
    public BookingResponse hold(CreateBookingRequest request) {
        Instant now = clock.instant();

        Event event = eventRepository.findById(request.eventId())
                .orElseThrow(() -> new ResourceNotFoundException("Event", request.eventId()));

        if (!event.isOnSaleAt(now)) {
            throw new ConflictException(
                    "Event %d is not on sale".formatted(request.eventId()));
        }

        // Settle any lapsed holds on this event first. Until they are settled
        // their seats read as HELD and stay unsellable, even though nobody has
        // a claim on them any more.
        settleLapsedHolds(request.eventId(), now);

        Set<Long> requestedIds = new LinkedHashSet<>(request.seatIds());
        List<EventSeat> seats = eventSeatRepository.findAllByIdOrdered(requestedIds);

        if (seats.size() != requestedIds.size()) {
            throw new ResourceNotFoundException("Seats", requestedIds);
        }

        boolean allBelongToEvent = seats.stream()
                .allMatch(seat -> seat.getEvent().getId().equals(request.eventId()));
        if (!allBelongToEvent) {
            throw new ConflictException(
                    "All seats must belong to event %d".formatted(request.eventId()));
        }

        // The race lives here: read, then write. See the method comment.
        seats.forEach(seat -> {
            if (!seat.isAvailable()) {
                throw new SeatUnavailableException(seat.getId(), seat.getStatus());
            }
        });

        BigDecimal total = seats.stream()
                .map(EventSeat::getPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Booking booking = new Booking(
                request.eventId(), request.userId(), total, now.plus(holdDuration));
        bookingRepository.saveAndFlush(booking);

        seats.forEach(seat -> seat.hold(booking.getId()));

        log.debug("Held {} seats for booking {} until {}",
                seats.size(), booking.getId(), booking.getExpiresAt());

        return BookingResponse.from(booking, seats);
    }

    /**
     * Confirms a held booking.
     *
     * <p>An idempotency key makes a repeated call safe. A client that times out
     * and retries must not be told its booking is in an impossible state, and
     * must not end up with two.
     */
    @Transactional
    public BookingResponse confirm(long bookingId, String idempotencyKey) {
        Instant now = clock.instant();
        Booking booking = requireBooking(bookingId);

        if (booking.getStatus() == BookingStatus.CONFIRMED) {
            if (idempotencyKey != null && idempotencyKey.equals(booking.getIdempotencyKey())) {
                log.debug("Replayed confirmation of booking {}", bookingId);
                return response(booking);
            }
            throw new ConflictException("Booking %d is already confirmed".formatted(bookingId));
        }

        // Settle the lapsed hold before refusing, so the seats go back on sale
        // rather than waiting for the worker. The caller gets 410 - the
        // reservation existed and is gone - not a generic conflict.
        if (booking.getStatus() == BookingStatus.PENDING && !booking.isHoldValidAt(now)) {
            expire(booking);
            throw new HoldExpiredException(bookingId);
        }

        List<EventSeat> seats = eventSeatRepository.findByBookingId(bookingId);
        booking.confirm(now, idempotencyKey);
        seats.forEach(EventSeat::markBooked);

        log.debug("Confirmed booking {} with {} seats", bookingId, seats.size());

        return BookingResponse.from(booking, seats);
    }

    @Transactional
    public BookingResponse cancel(long bookingId) {
        Booking booking = requireBooking(bookingId);

        List<EventSeat> seats = eventSeatRepository.findByBookingId(bookingId);
        booking.cancel();
        seats.forEach(EventSeat::release);

        log.debug("Cancelled booking {}, released {} seats", bookingId, seats.size());

        return BookingResponse.from(booking, seats);
    }

    /**
     * Reads a booking, settling a lapsed hold on the way.
     *
     * <p>Not read-only: a hold that has run out is expired here rather than
     * being reported as PENDING until a worker gets to it. A caller must never
     * see a reservation that is no longer real.
     */
    @Transactional
    public BookingResponse get(long bookingId) {
        Booking booking = requireBooking(bookingId);
        expireIfLapsed(booking, clock.instant());
        return response(booking);
    }

    @Transactional(readOnly = true)
    public Page<BookingResponse> listForUser(long userId, Pageable pageable) {
        return bookingRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(this::response);
    }

    /**
     * Turns a lapsed hold into an EXPIRED booking and frees its seats.
     *
     * <p>The scheduled worker in issue #8 will do the same thing in bulk. Doing
     * it here as well means correctness does not depend on the worker having
     * run: between a hold lapsing and the worker noticing there is no window in
     * which the booking still looks valid or its seats stay off the market.
     */
    private void expireIfLapsed(Booking booking, Instant now) {
        if (booking.getStatus() != BookingStatus.PENDING || booking.isHoldValidAt(now)) {
            return;
        }
        expire(booking);
    }

    private void expire(Booking booking) {
        eventSeatRepository.findByBookingId(booking.getId()).forEach(EventSeat::release);
        booking.expire();
        log.debug("Expired lapsed hold on booking {}", booking.getId());
    }

    private void settleLapsedHolds(Long eventId, Instant now) {
        List<Booking> lapsed = bookingRepository.findLapsedHolds(eventId, now);
        lapsed.forEach(this::expire);

        if (!lapsed.isEmpty()) {
            log.debug("Settled {} lapsed holds on event {}", lapsed.size(), eventId);
        }
    }

    private Booking requireBooking(long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));
    }

    private BookingResponse response(Booking booking) {
        return BookingResponse.from(booking, eventSeatRepository.findByBookingId(booking.getId()));
    }
}
