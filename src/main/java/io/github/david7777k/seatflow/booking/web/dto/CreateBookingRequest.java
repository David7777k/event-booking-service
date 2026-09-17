package io.github.david7777k.seatflow.booking.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateBookingRequest(

        @NotNull(message = "eventId is required")
        Long eventId,

        /**
         * Temporary. Until authentication lands in issue #9 there is no
         * authenticated principal to take this from, so the caller states who
         * they are. This is a hole - anyone can book as anyone - and it closes
         * when the identity comes from a verified token instead.
         */
        @NotNull(message = "userId is required")
        Long userId,

        @NotEmpty(message = "at least one seat is required")
        @Size(max = 10, message = "at most 10 seats can be booked at once")
        List<@NotNull(message = "seat id must not be null") Long> seatIds) {
}
