package io.github.david7777k.seatflow.booking.web;

import io.github.david7777k.seatflow.booking.service.BookingService;
import io.github.david7777k.seatflow.booking.web.dto.BookingResponse;
import io.github.david7777k.seatflow.booking.web.dto.CreateBookingRequest;
import io.github.david7777k.seatflow.security.web.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * Every endpoint here acts as the token holder.
 *
 * <p>No endpoint accepts a user id. The caller's identity comes from a token
 * the filter chain has already verified, so it cannot be claimed by the
 * request.
 */
@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /**
     * Holds seats. The response carries {@code expiresAt}, which is when the
     * hold lapses if it has not been confirmed.
     */
    @PostMapping
    public ResponseEntity<BookingResponse> createBooking(
            @Valid @RequestBody CreateBookingRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        BookingResponse booking = bookingService.hold(request, CurrentUser.id(jwt));

        URI location = UriComponentsBuilder.fromPath("/api/v1/bookings/{id}")
                .buildAndExpand(booking.id())
                .toUri();

        return ResponseEntity.created(location).body(booking);
    }

    /**
     * Confirms a hold.
     *
     * <p>{@code Idempotency-Key} is optional but strongly advised: without it a
     * client that retries after a timeout cannot tell a successful confirmation
     * from a failed one.
     *
     * <p>Not available to administrators on another user's behalf. Confirming
     * is an act of purchase, and nobody should be able to make one for someone
     * else.
     */
    @PostMapping("/{bookingId}/confirm")
    public BookingResponse confirmBooking(
            @PathVariable long bookingId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt) {

        return bookingService.confirm(bookingId, idempotencyKey, CurrentUser.id(jwt));
    }

    @DeleteMapping("/{bookingId}")
    public BookingResponse cancelBooking(@PathVariable long bookingId,
                                         @AuthenticationPrincipal Jwt jwt) {
        return bookingService.cancel(bookingId, CurrentUser.id(jwt), CurrentUser.isAdmin(jwt));
    }

    @GetMapping("/{bookingId}")
    public BookingResponse getBooking(@PathVariable long bookingId,
                                      @AuthenticationPrincipal Jwt jwt) {
        return bookingService.get(bookingId, CurrentUser.id(jwt), CurrentUser.isAdmin(jwt));
    }

    /** The caller's own bookings. There is no way to ask for anybody else's. */
    @GetMapping
    public Page<BookingResponse> listMyBookings(
            @AuthenticationPrincipal Jwt jwt,
            @PageableDefault(size = 20) Pageable pageable) {

        return bookingService.listForUser(CurrentUser.id(jwt), pageable);
    }
}
