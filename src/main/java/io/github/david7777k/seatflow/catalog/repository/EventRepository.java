package io.github.david7777k.seatflow.catalog.repository;

import io.github.david7777k.seatflow.catalog.domain.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {

    /**
     * Loads the event together with its venue.
     *
     * <p>The association is LAZY, and every response includes the venue name.
     * Without the join fetch, reading it outside the persistence context throws
     * (open-in-view is disabled) - and reading it inside would add a second
     * query per event.
     */
    @Query("select e from Event e join fetch e.venue where e.id = :eventId")
    Optional<Event> findByIdWithVenue(@Param("eventId") Long eventId);
}
