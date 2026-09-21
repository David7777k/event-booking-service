package io.github.david7777k.seatflow.security.domain;

public enum Role {

    /** Can book seats and manage their own bookings. */
    USER,

    /** Can also create venues and events, and publish or cancel them. */
    ADMIN
}
