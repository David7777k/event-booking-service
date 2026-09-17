package io.github.david7777k.seatflow.catalog.domain;

import io.github.david7777k.seatflow.common.error.ConflictException;

/**
 * A seat was asked for that somebody else already holds.
 *
 * <p>The seat id is named so a client can drop it from the selection and retry
 * with the rest, instead of being told only that something went wrong.
 */
public class SeatUnavailableException extends ConflictException {

    private final Long seatId;

    public SeatUnavailableException(Long eventSeatId, SeatStatus status) {
        super("Seat %d is not available (%s)".formatted(eventSeatId, status));
        this.seatId = eventSeatId;
    }

    public Long getSeatId() {
        return seatId;
    }
}
