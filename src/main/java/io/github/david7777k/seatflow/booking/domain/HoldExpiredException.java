package io.github.david7777k.seatflow.booking.domain;

/** A hold ran out before it was confirmed. Answered as 410, not 409. */
public class HoldExpiredException extends RuntimeException {

    public HoldExpiredException(Long bookingId) {
        super("Hold on booking %d has expired".formatted(bookingId));
    }
}
