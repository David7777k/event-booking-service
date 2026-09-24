package io.github.david7777k.seatflow.common.error;

/**
 * The request is well formed but cannot be applied to the current state -
 * a venue that already has a seat map, a seat already taken.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
