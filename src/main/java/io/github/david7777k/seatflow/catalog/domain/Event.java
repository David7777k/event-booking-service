package io.github.david7777k.seatflow.catalog.domain;

import io.github.david7777k.seatflow.common.error.ConflictException;
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
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Objects;

/**
 * An event scheduled at a venue.
 *
 * <p>The generated columns {@code time_range} and {@code search_vector} are not
 * mapped: they are derived by the database from the columns below and exist for
 * the overlap constraint and for full-text search. Mapping them would invite
 * code to write values the database owns.
 */
@Entity
@Table(name = "event")
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String description;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "sales_start_at", nullable = false)
    private Instant salesStartAt;

    @Column(name = "sales_end_at", nullable = false)
    private Instant salesEndAt;

    /**
     * STRING, never ORDINAL. An ordinal mapping stores enum positions, so
     * inserting a constant into the middle of the enum silently reinterprets
     * every row already written.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EventStatus status = EventStatus.DRAFT;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Event() {
        // required by JPA
    }

    public Event(Venue venue, String title, String description,
                 Instant startsAt, Instant endsAt,
                 Instant salesStartAt, Instant salesEndAt) {
        this.venue = venue;
        this.title = title;
        this.description = description;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.salesStartAt = salesStartAt;
        this.salesEndAt = salesEndAt;
        this.status = EventStatus.DRAFT;
    }

    /**
     * Applies a lifecycle transition, rejecting the ones the state machine does
     * not allow. Keeping this on the entity means no caller can move an event
     * to an arbitrary status by assigning a field.
     */
    public void transitionTo(EventStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new ConflictException(
                    "Event %d cannot move from %s to %s".formatted(id, status, target));
        }
        this.status = target;
    }

    /**
     * Whether the event accepts bookings right now. Being PUBLISHED is not
     * enough - the sales window has to be open too.
     */
    public boolean isOnSaleAt(Instant moment) {
        return status == EventStatus.PUBLISHED
                && !moment.isBefore(salesStartAt)
                && moment.isBefore(salesEndAt);
    }

    public Long getId() {
        return id;
    }

    public Venue getVenue() {
        return venue;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public Instant getSalesStartAt() {
        return salesStartAt;
    }

    public Instant getSalesEndAt() {
        return salesEndAt;
    }

    public EventStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Event event) || id == null) {
            return false;
        }
        return id.equals(event.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
