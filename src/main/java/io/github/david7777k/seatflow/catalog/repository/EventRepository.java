package io.github.david7777k.seatflow.catalog.repository;

import io.github.david7777k.seatflow.catalog.domain.Event;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

    /**
     * Full-text search over published events, with optional filters.
     *
     * <p>Native rather than JPQL because the interesting parts have no JPQL
     * equivalent: {@code @@} against the generated {@code tsvector} column,
     * which the GIN index serves, and {@code ts_rank} for ordering by
     * relevance. Expressing this through the Criteria API would mean falling
     * back to {@code LIKE '%...%'}, which cannot use an index at all and does
     * no stemming - a search for "conferences" would miss "conference".
     *
     * <p>Every nullable parameter is cast explicitly. PostgreSQL cannot infer
     * the type of a bare null parameter and fails to plan the statement.
     *
     * <p>{@code onlyAvailable} uses EXISTS rather than a join with a count:
     * it stops at the first free seat and is served by the partial index on
     * available seats, instead of counting every seat of every event.
     */
    @Query(value = """
            select e.id                as "id",
                   v.id                as "venueId",
                   v.name              as "venueName",
                   e.title             as "title",
                   e.description       as "description",
                   e.starts_at         as "startsAt",
                   e.ends_at           as "endsAt",
                   e.sales_start_at    as "salesStartAt",
                   e.sales_end_at      as "salesEndAt",
                   (select count(*) from event_seat es
                     where es.event_id = e.id and es.status = 'AVAILABLE') as "availableSeats"
            from event e
            join venue v on v.id = e.venue_id
            where e.status = 'PUBLISHED'
              and (cast(:query as text) is null
                   or e.search_vector @@ plainto_tsquery('english', cast(:query as text)))
              and (cast(:venueId as bigint) is null or e.venue_id = cast(:venueId as bigint))
              and (cast(:startingFrom as timestamptz) is null
                   or e.starts_at >= cast(:startingFrom as timestamptz))
              and (cast(:startingBefore as timestamptz) is null
                   or e.starts_at < cast(:startingBefore as timestamptz))
              and (:onlyAvailable = false
                   or exists (select 1 from event_seat es
                               where es.event_id = e.id and es.status = 'AVAILABLE'))
            order by
              case when cast(:query as text) is null then 0
                   else ts_rank(e.search_vector, plainto_tsquery('english', cast(:query as text)))
              end desc,
              e.starts_at asc
            """,
            countQuery = """
            select count(*)
            from event e
            where e.status = 'PUBLISHED'
              and (cast(:query as text) is null
                   or e.search_vector @@ plainto_tsquery('english', cast(:query as text)))
              and (cast(:venueId as bigint) is null or e.venue_id = cast(:venueId as bigint))
              and (cast(:startingFrom as timestamptz) is null
                   or e.starts_at >= cast(:startingFrom as timestamptz))
              and (cast(:startingBefore as timestamptz) is null
                   or e.starts_at < cast(:startingBefore as timestamptz))
              and (:onlyAvailable = false
                   or exists (select 1 from event_seat es
                               where es.event_id = e.id and es.status = 'AVAILABLE'))
            """,
            nativeQuery = true)
    Page<EventSearchRow> search(@Param("query") String query,
                                @Param("venueId") Long venueId,
                                @Param("startingFrom") Instant startingFrom,
                                @Param("startingBefore") Instant startingBefore,
                                @Param("onlyAvailable") boolean onlyAvailable,
                                Pageable pageable);
}
