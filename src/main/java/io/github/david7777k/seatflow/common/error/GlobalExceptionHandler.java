package io.github.david7777k.seatflow.common.error;

import io.github.david7777k.seatflow.booking.domain.HoldExpiredException;
import io.github.david7777k.seatflow.catalog.domain.SeatUnavailableException;
import org.hibernate.exception.ConstraintViolationException;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;
import java.util.TreeMap;

/**
 * Translates exceptions into RFC 9457 problem responses.
 *
 * <p>Errors are returned in a machine-readable shape rather than as free text,
 * so a client can branch on the status and on individual field errors without
 * parsing prose.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Resource not found");
        return problem;
    }

    /**
     * A seat somebody else already holds.
     *
     * <p>Handled ahead of the general conflict case so the response can name
     * the seat: a client that asked for four seats and lost one of them can
     * drop it and retry with the rest.
     */
    @ExceptionHandler(SeatUnavailableException.class)
    ProblemDetail handleSeatUnavailable(SeatUnavailableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Seat unavailable");
        problem.setProperty("seatId", ex.getSeatId());
        return problem;
    }

    /**
     * Wrong password, or an email with no account behind it.
     *
     * <p>One message for both. Saying which of the two failed hands an attacker
     * a way to discover which addresses are registered.
     */
    @ExceptionHandler(BadCredentialsException.class)
    ProblemDetail handleBadCredentials(BadCredentialsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Invalid email or password");
        problem.setTitle("Authentication failed");
        return problem;
    }

    /**
     * Authenticated, but not allowed to do this.
     *
     * <p>401 means "I do not know who you are", 403 means "I know, and the
     * answer is no". Returning 401 here would tell a client to go and get a
     * token it already has.
     */
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "You are not allowed to perform this action");
        problem.setTitle("Access denied");
        return problem;
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail handleConflict(ConflictException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Conflicting request");
        return problem;
    }

    /**
     * 410 rather than 409: the hold existed and no longer does. A client should
     * start over with a fresh selection, not retry the same request.
     */
    @ExceptionHandler(HoldExpiredException.class)
    ProblemDetail handleHoldExpired(HoldExpiredException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.GONE, ex.getMessage());
        problem.setTitle("Hold expired");
        return problem;
    }

    /**
     * Bean Validation failures. Field errors are returned as a map so a client
     * can attach each message to the input that produced it.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        Map<String, String> fieldErrors = new TreeMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setTitle("Invalid request");
        problem.setProperty("errors", fieldErrors);

        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * A constraint rejected the write.
     *
     * <p>This is not a failure of the database but the database doing its job:
     * every invariant this service depends on is expressed as a constraint, so
     * a violation means application code tried something it should not have.
     * It is reported as a conflict rather than a server error, and the driver
     * message is logged rather than returned - it leaks table and column names.
     */
    /**
     * Constraints whose violation a client can act on, mapped to a message that
     * says what actually happened.
     *
     * <p>Only constraints listed here get a specific message. Anything else
     * falls back to a generic conflict: an unrecognised violation is a bug in
     * this service, and guessing at an explanation would mislead the caller.
     */
    private static final Map<String, String> CONSTRAINT_MESSAGES = Map.of(
            "event_no_overlap_per_venue",
            "The venue already hosts an event during this time range",

            "event_seat_unique",
            "That seat is already offered at this event",

            "seat_unique_in_venue",
            "That seat already exists at this venue",

            "app_user_email_key",
            "An account with this email already exists");

    private static final String GENERIC_CONFLICT = "Request conflicts with existing data";

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleIntegrityViolation(DataIntegrityViolationException ex) {
        String constraint = constraintNameOf(ex);

        // getOrDefault is not null-safe here: Map.of produces an immutable map
        // that throws on a null key even on lookup, and the constraint name is
        // absent for some violations.
        String detail = constraint == null
                ? GENERIC_CONFLICT
                : CONSTRAINT_MESSAGES.getOrDefault(constraint, GENERIC_CONFLICT);

        log.warn("Constraint {} rejected a write", constraint == null ? "(unknown)" : constraint, ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail);
        problem.setTitle("Conflicting request");
        return problem;
    }

    /**
     * Walks the cause chain for the name of the constraint that rejected the
     * write.
     *
     * <p>Hibernate reports it for a unique violation but leaves it null for an
     * exclusion violation, so the driver is consulted as well: PostgreSQL sends
     * the constraint name as a field of the error, and reading that field is
     * sound in a way that parsing the message text is not.
     *
     * <p>This ties the handler to PostgreSQL. That is already true of the whole
     * project - exclusion constraints, generated tsvector columns and row-level
     * locking are all PostgreSQL behaviour - so the dependency is acknowledged
     * rather than hidden.
     *
     * <p>The message itself is never returned to the client: it carries table
     * and column names.
     */
    private static String constraintNameOf(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && violation.getConstraintName() != null) {
                return violation.getConstraintName();
            }
            if (cause instanceof PSQLException psql) {
                ServerErrorMessage error = psql.getServerErrorMessage();
                if (error != null && error.getConstraint() != null) {
                    return error.getConstraint();
                }
            }
            if (cause == cause.getCause()) {
                break;
            }
        }
        return null;
    }

    /**
     * Somebody changed the row between our reading it and writing it.
     *
     * <p>The booking path locks its rows and should never reach this, but any
     * path that updates a seat without locking first would, and the version
     * column exists precisely to catch that. Reported as a conflict, since from
     * the caller's side it is the same situation as losing a seat.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLockFailure(OptimisticLockingFailureException ex) {
        log.warn("Concurrent modification detected", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "The resource was modified concurrently, please retry");
        problem.setTitle("Concurrent modification");
        return problem;
    }

    /**
     * The lock could not be taken within the timeout.
     *
     * <p>503 with Retry-After, not 409: nothing about the request is wrong and
     * repeating it may well succeed. This is back pressure, and saying so lets
     * a client back off instead of hammering.
     */
    @ExceptionHandler(CannotAcquireLockException.class)
    ResponseEntity<ProblemDetail> handleLockTimeout(CannotAcquireLockException ex) {
        log.warn("Timed out waiting for a row lock", ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "The seats you asked for are busy right now, please retry");
        problem.setTitle("Busy");

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "2")
                .body(problem);
    }

    /**
     * Last resort. The cause is logged with its stack trace but never returned:
     * internal detail is as useful to an attacker as it is to us.
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
        problem.setTitle("Internal server error");
        return problem;
    }
}
