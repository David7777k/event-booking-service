package io.github.david7777k.seatflow.booking.repository;

import io.github.david7777k.seatflow.booking.domain.Booking;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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
}
