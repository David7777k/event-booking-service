package io.github.david7777k.seatflow.booking.domain;

/**
 * A hold ran out before it was confirmed.
 *
 * <p>Reported as 410 Gone rather than 409: the reservation genuinely existed
 * and no longer does, which is a different thing for a client to handle than a
 * request that conflicts with current state.
 */
public class HoldExpiredException extends RuntimeException {

    public HoldExpiredException(Long bookingId) {
        super("Hold on booking %d has expired".formatted(bookingId));
    }
}
