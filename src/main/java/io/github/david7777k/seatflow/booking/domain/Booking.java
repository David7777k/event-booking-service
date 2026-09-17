package io.github.david7777k.seatflow.booking.domain;

import io.github.david7777k.seatflow.common.error.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "booking")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status = BookingStatus.PENDING;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Booking() {
        // required by JPA
    }

    public Booking(Long eventId, Long userId, BigDecimal totalAmount, Instant expiresAt) {
        this.eventId = eventId;
        this.userId = userId;
        this.totalAmount = totalAmount;
        this.expiresAt = expiresAt;
        this.status = BookingStatus.PENDING;
    }

    /**
     * A hold is only good while it is PENDING and has not run out.
     *
     * <p>Checked on every read, not only by the expiry worker. Between the
     * moment a hold lapses and the moment the worker notices, the row still
     * says PENDING - treating that as valid would let a booking be confirmed
     * after its seats were effectively free.
     */
    public boolean isHoldValidAt(Instant moment) {
        return status == BookingStatus.PENDING
                && expiresAt != null
                && moment.isBefore(expiresAt);
    }

    public void confirm(Instant moment, String idempotencyKey) {
        requireTransitionTo(BookingStatus.CONFIRMED);

        if (!isHoldValidAt(moment)) {
            throw new HoldExpiredException(id);
        }

        this.status = BookingStatus.CONFIRMED;
        this.confirmedAt = moment;
        this.idempotencyKey = idempotencyKey;
        this.expiresAt = null;
    }

    public void cancel() {
        requireTransitionTo(BookingStatus.CANCELLED);
        this.status = BookingStatus.CANCELLED;
        this.expiresAt = null;
    }

    public void expire() {
        requireTransitionTo(BookingStatus.EXPIRED);
        this.status = BookingStatus.EXPIRED;
        this.expiresAt = null;
    }

    private void requireTransitionTo(BookingStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new ConflictException(
                    "Booking %d cannot move from %s to %s".formatted(id, status, target));
        }
    }

    public Long getId() {
        return id;
    }

    public Long getEventId() {
        return eventId;
    }

    public Long getUserId() {
        return userId;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Booking booking) || id == null) {
            return false;
        }
        return id.equals(booking.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
