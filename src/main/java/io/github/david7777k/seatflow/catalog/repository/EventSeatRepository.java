package io.github.david7777k.seatflow.catalog.repository;

import io.github.david7777k.seatflow.catalog.domain.EventSeat;
import io.github.david7777k.seatflow.catalog.domain.SeatStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface EventSeatRepository extends JpaRepository<EventSeat, Long> {

    long countByEventId(Long eventId);

    long countByEventIdAndStatus(Long eventId, SeatStatus status);

    boolean existsByEventId(Long eventId);

    /**
     * The seat map of an event.
     *
     * <p>The join fetch matters: without it every row would fetch its Seat
     * separately, which on a seat map is the worst possible N+1.
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

    /**
     * Loads the requested seats with their physical seat attached, ordered by
     * id.
     *
     * <p>Ordered by id to match the locking query, which needs a deterministic
     * acquisition order.
     */
    @Query("""
            select es from EventSeat es
            join fetch es.seat
            where es.id in :ids
            order by es.id
            """)
    List<EventSeat> findAllByIdOrdered(@Param("ids") Collection<Long> ids);

    @Query("""
            select es from EventSeat es
            join fetch es.seat
            where es.bookingId = :bookingId
            order by es.id
            """)
    List<EventSeat> findByBookingId(@Param("bookingId") Long bookingId);

    /**
     * Takes a row-level write lock on the requested seats.
     *
     * <p>Two details are load-bearing. {@code order by es.id} prevents a
     * deadlock between requests naming the same seats in different orders. And
     * there is no join fetch, because FOR UPDATE applies to every table in the
     * statement and would lock physical seats of unrelated events too.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select es from EventSeat es
            where es.id in :ids
            order by es.id
            """)
    List<EventSeat> lockAllByIdOrdered(@Param("ids") Collection<Long> ids);
}
