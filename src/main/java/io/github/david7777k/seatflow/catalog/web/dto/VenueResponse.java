package io.github.david7777k.seatflow.catalog.web.dto;

import io.github.david7777k.seatflow.catalog.domain.Venue;

import java.time.Instant;

public record VenueResponse(
        Long id,
        String name,
        String address,
        long seatCount,
        Instant createdAt) {

    public static VenueResponse from(Venue venue, long seatCount) {
        return new VenueResponse(
                venue.getId(),
                venue.getName(),
                venue.getAddress(),
                seatCount,
                venue.getCreatedAt());
    }
}
