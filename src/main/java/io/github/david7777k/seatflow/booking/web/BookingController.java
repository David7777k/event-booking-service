package io.github.david7777k.seatflow.booking.web;

import io.github.david7777k.seatflow.booking.service.BookingService;
import io.github.david7777k.seatflow.booking.web.dto.BookingResponse;
import io.github.david7777k.seatflow.booking.web.dto.CreateBookingRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

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
    public ResponseEntity<BookingResponse> createBooking(@Valid @RequestBody CreateBookingRequest request) {
        BookingResponse booking = bookingService.hold(request);

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
     */
    @PostMapping("/{bookingId}/confirm")
    public BookingResponse confirmBooking(
            @PathVariable long bookingId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return bookingService.confirm(bookingId, idempotencyKey);
    }

    @DeleteMapping("/{bookingId}")
    public BookingResponse cancelBooking(@PathVariable long bookingId) {
        return bookingService.cancel(bookingId);
    }

    @GetMapping("/{bookingId}")
    public BookingResponse getBooking(@PathVariable long bookingId) {
        return bookingService.get(bookingId);
    }

    /**
     * Bookings of one user.
     *
     * <p>{@code userId} is a query parameter only until issue #9 supplies an
     * authenticated principal. Until then anyone can read anyone's bookings,
     * which is recorded here rather than left to be discovered.
     */
    @GetMapping
    public Page<BookingResponse> listBookings(
            @RequestParam long userId,
            @PageableDefault(size = 20) Pageable pageable) {
        return bookingService.listForUser(userId, pageable);
    }
}
