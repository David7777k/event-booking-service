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

    /**
     * Loads the requested seats with their physical seat attached, ordered by
     * id.
     *
     * <p>The ordering is not cosmetic. Once issue #7 turns this into a locking
     * query, two requests asking for the same seats in different orders would
     * deadlock without a deterministic acquisition order. Establishing it here
     * keeps that change to a single annotation.
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
     * <p>Issues {@code SELECT ... FOR UPDATE}. A competing transaction asking
     * for the same seat blocks here, and when it is let through it reads the
     * row as it now stands rather than as it stood before the winner wrote.
     * That is what turns "read, then write" into a decision made once.
     *
     * <p>{@code order by es.id} is required for correctness, not tidiness.
     * Locks are taken row by row in the order rows are returned, so two
     * requests for seats {1, 2} and {2, 1} would each hold one and wait for the
     * other - a deadlock the database would resolve by killing one of them.
     * A fixed acquisition order makes that impossible.
     *
     * <p>No {@code join fetch} here: FOR UPDATE applies to every table in the
     * statement, and locking each physical seat as well would block unrelated
     * events that merely use the same venue. Attributes are read afterwards by
     * {@link #findAllByIdOrdered}, which finds the rows already in the
     * persistence context.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select es from EventSeat es
            where es.id in :ids
            order by es.id
            """)
    List<EventSeat> lockAllByIdOrdered(@Param("ids") Collection<Long> ids);
}
