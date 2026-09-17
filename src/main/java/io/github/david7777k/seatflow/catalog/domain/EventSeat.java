package io.github.david7777k.seatflow.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One seat offered at one event: its price, and whether it is still available.
 *
 * <p>The unique constraint on (event_id, seat_id) makes this exactly one row
 * per seat per event. A row cannot hold two states at once, which is what makes
 * selling the same seat twice impossible to express in the schema - regardless
 * of what the service layer does.
 */
@Entity
@Table(name = "event_seat")
public class EventSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;

    @Column(nullable = false)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatStatus status = SeatStatus.AVAILABLE;

    @Column(name = "booking_id")
    private Long bookingId;

    /**
     * Mapped now, used in issue #7. With a version column Hibernate appends
     * {@code and version = ?} to every update and fails when it matches no row,
     * which is the optimistic half of the overbooking comparison. The
     * pessimistic half is a locking query on the same rows.
     */
    @Version
    @Column(nullable = false)
    private long version;

    protected EventSeat() {
        // required by JPA
    }

    public Long getId() {
        return id;
    }

    public Event getEvent() {
        return event;
    }

    public Seat getSeat() {
        return seat;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public SeatStatus getStatus() {
        return status;
    }

    public Long getBookingId() {
        return bookingId;
    }

    public long getVersion() {
        return version;
    }

    public boolean isAvailable() {
        return status == SeatStatus.AVAILABLE;
    }

    /**
     * Claims the seat for a booking.
     *
     * <p>The status check here is not what makes double-booking impossible:
     * between reading this row and writing it, another transaction can do the
     * same. Issue #7 closes that window. The database keeps the invariant that
     * a taken seat always names its booking, whatever this method does.
     */
    public void hold(Long bookingId) {
        if (status != SeatStatus.AVAILABLE) {
            throw new SeatUnavailableException(id, status);
        }
        this.status = SeatStatus.HELD;
        this.bookingId = bookingId;
    }

    public void markBooked() {
        if (status != SeatStatus.HELD) {
            throw new SeatUnavailableException(id, status);
        }
        this.status = SeatStatus.BOOKED;
    }

    /** Returns the seat to circulation when its booking ends without a sale. */
    public void release() {
        this.status = SeatStatus.AVAILABLE;
        this.bookingId = null;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof EventSeat eventSeat) || id == null) {
            return false;
        }
        return id.equals(eventSeat.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
