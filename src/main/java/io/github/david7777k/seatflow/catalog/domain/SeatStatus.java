package io.github.david7777k.seatflow.catalog.domain;

public enum SeatStatus {

    /** Nobody holds it. The only status a booking may claim from. */
    AVAILABLE,

    /** Reserved by a pending booking that has not been confirmed yet. */
    HELD,

    /** Sold. */
    BOOKED
}
