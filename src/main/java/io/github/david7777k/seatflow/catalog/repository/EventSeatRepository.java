package io.github.david7777k.seatflow.catalog.repository;

import io.github.david7777k.seatflow.catalog.domain.EventSeat;
import io.github.david7777k.seatflow.catalog.domain.SeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EventSeatRepository extends JpaRepository<EventSeat, Long> {

    long countByEventId(Long eventId);

    long countByEventIdAndStatus(Long eventId, SeatStatus status);

    boolean existsByEventId(Long eventId);

    /**
     * The seat map of an event.
     *
     * <p>{@code join fetch es.seat} is load-bearing: every row in the response
     * needs the section, row and number, and without it each of them would
     * trigger its own select - the N+1 that a seat map makes very expensive.
     */
    @Query("""
            select es from EventSeat es
            join fetch es.seat s
            where es.event.id = :eventId
              and (:section is null or s.section = :section)
              and (:onlyAvailable = false or es.status = io.github.david7777k.seatflow.catalog.domain.SeatStatus.AVAILABLE)
            order by s.section, s.rowLabel, s.seatNumber
            """)
    List<EventSeat> findSeatMap(@Param("eventId") Long eventId,
                                @Param("section") String section,
                                @Param("onlyAvailable") boolean onlyAvailable);
}
