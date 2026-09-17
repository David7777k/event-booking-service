package io.github.david7777k.seatflow.catalog.web.dto;

import io.github.david7777k.seatflow.catalog.repository.EventSearchRow;

import java.time.Instant;

public record EventSummaryResponse(
        Long id,
        Long venueId,
        String venueName,
        String title,
        String description,
        Instant startsAt,
        Instant endsAt,
        Instant salesStartAt,
        Instant salesEndAt,
        long availableSeats) {

    public static EventSummaryResponse from(EventSearchRow row) {
        return new EventSummaryResponse(
                row.getId(),
                row.getVenueId(),
                row.getVenueName(),
                row.getTitle(),
                row.getDescription(),
                row.getStartsAt(),
                row.getEndsAt(),
                row.getSalesStartAt(),
                row.getSalesEndAt(),
                row.getAvailableSeats());
    }
}
