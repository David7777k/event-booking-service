package io.github.david7777k.seatflow.booking.domain;

public enum BookingStatus {

    /** Seats are held but not paid for. Expires if not confirmed in time. */
    PENDING,

    /** Confirmed. The seats are sold. */
    CONFIRMED,

    /** Released by the user before confirming. */
    CANCELLED,

    /** Released because the hold ran out. */
    EXPIRED;

    public boolean canTransitionTo(BookingStatus target) {
        return switch (this) {
            case PENDING -> target != PENDING;
            case CONFIRMED, CANCELLED, EXPIRED -> false;
        };
    }

    public boolean isActive() {
        return this == PENDING || this == CONFIRMED;
    }
}
