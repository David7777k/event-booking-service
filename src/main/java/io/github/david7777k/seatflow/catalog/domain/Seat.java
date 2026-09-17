package io.github.david7777k.seatflow.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * A physical seat at a venue. It exists independently of any event: what the
 * seat costs and whether it is taken belongs to event_seat, not here.
 */
@Entity
@Table(name = "seat")
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // LAZY deliberately: rendering a seat map must not fetch the venue per seat.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    @Column(nullable = false)
    private String section;

    @Column(name = "row_label", nullable = false)
    private String rowLabel;

    @Column(name = "seat_number", nullable = false)
    private int seatNumber;

    protected Seat() {
        // required by JPA
    }

    public Seat(Venue venue, String section, String rowLabel, int seatNumber) {
        this.venue = venue;
        this.section = section;
        this.rowLabel = rowLabel;
        this.seatNumber = seatNumber;
    }

    public Long getId() {
        return id;
    }

    public Venue getVenue() {
        return venue;
    }

    public String getSection() {
        return section;
    }

    public String getRowLabel() {
        return rowLabel;
    }

    public int getSeatNumber() {
        return seatNumber;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Seat seat) || id == null) {
            return false;
        }
        return id.equals(seat.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
