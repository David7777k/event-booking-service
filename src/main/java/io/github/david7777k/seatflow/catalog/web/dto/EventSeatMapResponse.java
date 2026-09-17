package io.github.david7777k.seatflow.catalog.web.dto;

import java.util.List;

public record EventSeatMapResponse(
        Long eventId,
        int returnedSeats,
        long availableSeats,
        List<EventSeatResponse> seats) {
}
