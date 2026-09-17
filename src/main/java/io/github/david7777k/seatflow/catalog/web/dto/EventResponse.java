package io.github.david7777k.seatflow.catalog.web.dto;

import io.github.david7777k.seatflow.catalog.domain.Event;
import io.github.david7777k.seatflow.catalog.domain.EventStatus;

import java.time.Instant;

public record EventResponse(
        Long id,
        Long venueId,
        String venueName,
        String title,
        String description,
        Instant startsAt,
        Instant endsAt,
        Instant salesStartAt,
        Instant salesEndAt,
        EventStatus status,
        long totalSeats,
        long availableSeats,
        Instant createdAt) {

    public static EventResponse from(Event event, long totalSeats, long availableSeats) {
        return new EventResponse(
                event.getId(),
                event.getVenue().getId(),
                event.getVenue().getName(),
                event.getTitle(),
                event.getDescription(),
                event.getStartsAt(),
                event.getEndsAt(),
                event.getSalesStartAt(),
                event.getSalesEndAt(),
                event.getStatus(),
                totalSeats,
                availableSeats,
                event.getCreatedAt());
    }
}
