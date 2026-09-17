package io.github.david7777k.seatflow.catalog.web.dto;

import io.github.david7777k.seatflow.catalog.domain.Seat;

public record SeatResponse(
        Long id,
        String section,
        String rowLabel,
        int seatNumber) {

    public static SeatResponse from(Seat seat) {
        return new SeatResponse(
                seat.getId(),
                seat.getSection(),
                seat.getRowLabel(),
                seat.getSeatNumber());
    }
}
