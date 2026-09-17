package io.github.david7777k.seatflow.catalog.repository;

import io.github.david7777k.seatflow.catalog.domain.Venue;
import io.github.david7777k.seatflow.catalog.web.dto.VenueResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface VenueRepository extends JpaRepository<Venue, Long> {

    /**
     * Venues with their seat counts in a single query.
     *
     * <p>The obvious alternative - fetch the page, then count seats per venue -
     * is the textbook N+1: one query for twenty venues becomes twenty-one.
     *
     * <p>The count query is spelled out because Spring Data cannot reliably
     * derive one from a grouped query: the default would count grouped rows in
     * a way that reports the wrong total.
     */
    @Query(value = """
            select new io.github.david7777k.seatflow.catalog.web.dto.VenueResponse(
                v.id, v.name, v.address, count(s.id), v.createdAt)
            from Venue v
            left join Seat s on s.venue = v
            group by v.id, v.name, v.address, v.createdAt
            """,
            countQuery = "select count(v) from Venue v")
    Page<VenueResponse> findAllWithSeatCount(Pageable pageable);
}
