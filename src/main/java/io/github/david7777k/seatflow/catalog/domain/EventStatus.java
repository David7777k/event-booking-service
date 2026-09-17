package io.github.david7777k.seatflow.catalog.domain;

public enum EventStatus {

    /** Created but not on sale. Seats exist; nobody can book them yet. */
    DRAFT,

    /** On sale. The only status from which a booking may be created. */
    PUBLISHED,

    /**
     * Called off. Excluded from the venue overlap constraint, so cancelling an
     * event frees its slot for another one.
     */
    CANCELLED;

    public boolean canTransitionTo(EventStatus target) {
        return switch (this) {
            case DRAFT -> target == PUBLISHED || target == CANCELLED;
            case PUBLISHED -> target == CANCELLED;
            case CANCELLED -> false;
        };
    }
}
