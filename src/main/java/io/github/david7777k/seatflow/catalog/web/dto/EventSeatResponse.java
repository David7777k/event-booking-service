package io.github.david7777k.seatflow.catalog.web.dto;

import io.github.david7777k.seatflow.catalog.domain.EventSeat;
import io.github.david7777k.seatflow.catalog.domain.SeatStatus;

import java.math.BigDecimal;

public record EventSeatResponse(
        Long id,
        String section,
        String rowLabel,
        int seatNumber,
        BigDecimal price,
        SeatStatus status) {

    public static EventSeatResponse from(EventSeat eventSeat) {
        return new EventSeatResponse(
                eventSeat.getId(),
                eventSeat.getSeat().getSection(),
                eventSeat.getSeat().getRowLabel(),
                eventSeat.getSeat().getSeatNumber(),
                eventSeat.getPrice(),
                eventSeat.getStatus());
    }
}
