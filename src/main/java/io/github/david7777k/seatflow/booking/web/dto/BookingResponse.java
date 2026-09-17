package io.github.david7777k.seatflow.booking.web.dto;

import io.github.david7777k.seatflow.booking.domain.Booking;
import io.github.david7777k.seatflow.booking.domain.BookingStatus;
import io.github.david7777k.seatflow.catalog.domain.EventSeat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BookingResponse(
        Long id,
        Long eventId,
        Long userId,
        BookingStatus status,
        BigDecimal totalAmount,
        Instant expiresAt,
        Instant confirmedAt,
        Instant createdAt,
        List<BookedSeat> seats) {

    public record BookedSeat(
            Long eventSeatId,
            String section,
            String rowLabel,
            int seatNumber,
            BigDecimal price) {

        static BookedSeat from(EventSeat eventSeat) {
            return new BookedSeat(
                    eventSeat.getId(),
                    eventSeat.getSeat().getSection(),
                    eventSeat.getSeat().getRowLabel(),
                    eventSeat.getSeat().getSeatNumber(),
                    eventSeat.getPrice());
        }
    }

    public static BookingResponse from(Booking booking, List<EventSeat> seats) {
        return new BookingResponse(
                booking.getId(),
                booking.getEventId(),
                booking.getUserId(),
                booking.getStatus(),
                booking.getTotalAmount(),
                booking.getExpiresAt(),
                booking.getConfirmedAt(),
                booking.getCreatedAt(),
                seats.stream().map(BookedSeat::from).toList());
    }
}
