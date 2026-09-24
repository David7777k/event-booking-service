package io.github.david7777k.seatflow.catalog.repository;

import java.time.Instant;

/**
 * Projection for the search query: needs the venue name and a seat count that
 * Event does not carry, and avoids a query per row for the LAZY venue.
 */
public interface EventSearchRow {

    Long getId();

    Long getVenueId();

    String getVenueName();

    String getTitle();

    String getDescription();

    Instant getStartsAt();

    Instant getEndsAt();

    Instant getSalesStartAt();

    Instant getSalesEndAt();

    long getAvailableSeats();
}
