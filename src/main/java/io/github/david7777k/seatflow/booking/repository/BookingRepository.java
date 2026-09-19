package io.github.david7777k.seatflow.booking.repository;

import io.github.david7777k.seatflow.booking.domain.Booking;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Page<Booking> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /**
     * Looks up a previous confirmation by its idempotency key.
     *
     * <p>Scoped by user because the unique index is, so two clients choosing the
     * same key cannot see each other's bookings.
     */
    Optional<Booking> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    /**
     * Holds on this event that have run out but are still marked PENDING.
     *
     * <p>Their seats are unsellable until something settles them. The scheduled
     * worker in issue #8 will do that in the background; until then, and in any
     * case between worker runs, a booking attempt settles the ones in its way
     * so a lapsed hold cannot keep a seat off the market.
     */
    @Query("""
            select b from Booking b
            where b.eventId = :eventId
              and b.status = io.github.david7777k.seatflow.booking.domain.BookingStatus.PENDING
              and b.expiresAt < :now
            """)
    List<Booking> findLapsedHolds(@Param("eventId") Long eventId, @Param("now") Instant now);

    /**
     * Claims a batch of lapsed holds for this worker, skipping any another
     * worker is already holding.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what makes several instances of this
     * service safe to run at once. Without {@code FOR UPDATE} two workers would
     * both read the same rows and both try to expire them. With {@code FOR
     * UPDATE} alone the second worker would block until the first committed,
     * turning parallel workers into a queue. {@code SKIP LOCKED} tells
     * PostgreSQL to pass over rows that are already locked, so each worker
     * walks away with a disjoint batch and they make progress in parallel.
     *
     * <p>Native SQL because JPA has no portable way to express SKIP LOCKED;
     * only ids are returned, and the rows are then loaded as entities inside
     * the same transaction, still holding the locks.
     *
     * <p>Ordered by expiry so the holds that lapsed longest ago are released
     * first, and their seats spend the least time off the market.
     */
    @Query(value = """
            select b.id
            from booking b
            where b.status = 'PENDING'
              and b.expires_at < :now
            order by b.expires_at
            limit :batchSize
            for update skip locked
            """, nativeQuery = true)
    List<Long> claimLapsedHoldIds(@Param("now") Instant now, @Param("batchSize") int batchSize);

    List<Booking> findByIdIn(Collection<Long> ids);
}
