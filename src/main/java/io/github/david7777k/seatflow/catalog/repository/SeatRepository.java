package io.github.david7777k.seatflow.catalog.repository;

import io.github.david7777k.seatflow.catalog.domain.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {

    boolean existsByVenueId(Long venueId);

    long countByVenueId(Long venueId);

    /**
     * Ordered by section, then row, then number so the caller receives a seat
     * map it can render directly instead of sorting it again.
     *
     * <p>{@code section} is optional: passing null returns the whole venue. A
     * single query with a null check beats two near-identical methods.
     */
    @Query("""
            select s from Seat s
            where s.venue.id = :venueId
              and (:section is null or s.section = :section)
            order by s.section, s.rowLabel, s.seatNumber
            """)
    List<Seat> findSeatMap(@Param("venueId") Long venueId, @Param("section") String section);
}
