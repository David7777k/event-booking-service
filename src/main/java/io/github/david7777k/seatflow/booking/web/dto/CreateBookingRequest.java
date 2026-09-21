package io.github.david7777k.seatflow.booking.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * There is deliberately no userId here.
 *
 * <p>It used to be a field, which meant a caller could book as anybody simply
 * by writing someone else's id. Identity now comes from the verified token and
 * cannot be asserted by the request.
 */
public record CreateBookingRequest(

        @NotNull(message = "eventId is required")
        Long eventId,

        @NotEmpty(message = "at least one seat is required")
        @Size(max = 10, message = "at most 10 seats can be booked at once")
        List<@NotNull(message = "seat id must not be null") Long> seatIds) {
}
