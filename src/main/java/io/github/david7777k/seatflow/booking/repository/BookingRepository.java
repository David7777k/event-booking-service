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

    /** Scoped by user, like the unique index: keys cannot collide across users. */
    Optional<Booking> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    /**
     * Lapsed holds still marked PENDING. Their seats stay unsellable until
     * something settles them, so a booking attempt clears the ones in its way
     * rather than waiting for the sweep.
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
     * <p>SKIP LOCKED is what makes several workers safe together: without
     * FOR UPDATE they would both expire the same rows, and with FOR UPDATE
     * alone the second would block until the first commits.
     *
     * <p>Native SQL because JPA cannot express SKIP LOCKED. Only ids come back;
     * the rows load as entities in the same transaction, still locked.
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
