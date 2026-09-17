package io.github.david7777k.seatflow.catalog.web.dto;

import java.util.List;

public record SeatMapResponse(
        Long venueId,
        int totalSeats,
        List<SeatResponse> seats) {
}
