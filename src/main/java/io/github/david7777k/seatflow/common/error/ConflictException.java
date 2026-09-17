package io.github.david7777k.seatflow.common.error;

/**
 * The request is well formed but cannot be applied to the current state of the
 * resource - a venue that already has a seat map, a seat already taken.
 *
 * <p>Distinct from a validation failure, which means the request itself is
 * malformed and would be wrong against any state.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
