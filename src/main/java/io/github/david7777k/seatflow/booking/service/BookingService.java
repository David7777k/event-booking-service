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
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final Duration holdDuration;
    private final String lockTimeout;

    public BookingService(BookingRepository bookingRepository,
                          EventRepository eventRepository,
                          EventSeatRepository eventSeatRepository,
                          JdbcTemplate jdbcTemplate,
                          Clock clock,
                          @Value("${seatflow.booking.hold-duration:PT10M}") Duration holdDuration,
                          @Value("${seatflow.booking.lock-timeout:3s}") String lockTimeout) {
        this.bookingRepository = bookingRepository;
        this.eventRepository = eventRepository;
        this.eventSeatRepository = eventSeatRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.holdDuration = holdDuration;
        this.lockTimeout = lockTimeout;
    }

    /**
     * Holds the requested seats for a limited time.
     *
     * <h4>Why the seats are locked</h4>
     *
     * Reading a seat, checking it is free, and then writing it is not safe
     * under concurrency: two requests can both pass the check before either
     * writes. {@code @Transactional} does not help - it provides atomicity, not
     * isolation from a concurrent writer, and under PostgreSQL's default
     * READ COMMITTED both transactions work from a snapshot that predates the
     * other's write.
     *
     * <h4>Why pessimistic rather than optimistic</h4>
     *
     * The {@code @Version} column on EventSeat already prevented a seat from
     * being sold twice - Hibernate appends {@code and version = ?} to the
     * update and the losers' updates match no row. Measured with 24 requests
     * for one seat, it held: exactly one booking was created. But the other 23
     * surfaced as ObjectOptimisticLockingFailureException after doing all their
     * work, which is a 500 for the caller and 23 wasted transactions.
     *
     * <p>Optimistic locking is the right tool when conflicts are rare, because
     * it costs nothing when there is no contention. Selling the last seat of a
     * popular event is the opposite case: contention is the normal state, and
     * the cheap path is never taken. Locking the rows up front turns the
     * contenders into a queue where each gets a definitive answer on its first
     * attempt, instead of a crowd that all do the work and all but one throw it
     * away.
     *
     * <p>The version column stays. It costs one integer comparison and guards
     * any path that updates a seat without taking the lock first.
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

        // Bound the wait. Without a timeout a contending request blocks until
        // the holder's transaction ends, and a stuck transaction would hang
        // every caller behind it instead of failing a few of them quickly.
        jdbcTemplate.queryForObject(
                "select set_config('lock_timeout', ?, true)", String.class, lockTimeout);

        // Take the locks, then read the attributes. Everything from here to
        // commit is the only writer for these rows.
        eventSeatRepository.lockAllByIdOrdered(requestedIds);
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

        // Safe now: these rows are locked, so a status read here cannot be
        // stale by the time it is acted on.
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
