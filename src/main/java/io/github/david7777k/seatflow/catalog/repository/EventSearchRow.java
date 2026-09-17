package io.github.david7777k.seatflow.catalog.repository;

import java.time.Instant;

/**
 * Projection for the search query.
 *
 * <p>An interface projection rather than the {@code Event} entity: the result
 * needs the venue name and a seat count that the entity does not carry, and
 * returning entities would either detach them or trigger a query per row for
 * the LAZY venue.
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
