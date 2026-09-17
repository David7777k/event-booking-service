package io.github.david7777k.seatflow.catalog.web;

import io.github.david7777k.seatflow.catalog.service.EventService;
import io.github.david7777k.seatflow.catalog.web.dto.CreateEventRequest;
import io.github.david7777k.seatflow.catalog.web.dto.EventResponse;
import io.github.david7777k.seatflow.catalog.web.dto.EventSeatMapResponse;
import io.github.david7777k.seatflow.catalog.web.dto.EventSummaryResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    public ResponseEntity<EventResponse> createEvent(@Valid @RequestBody CreateEventRequest request) {
        EventResponse event = eventService.create(request);

        URI location = UriComponentsBuilder.fromPath("/api/v1/events/{id}")
                .buildAndExpand(event.id())
                .toUri();

        return ResponseEntity.created(location).body(event);
    }

    /**
     * Searches published events. All parameters are optional: with none of them
     * this is simply the catalogue, ordered by start time.
     *
     * <p>Ordering is fixed — relevance, then start time — and deliberately not
     * exposed as a sort parameter.
     */
    @GetMapping
    public Page<EventSummaryResponse> searchEvents(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long venueId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "false") boolean onlyAvailable,
            @PageableDefault(size = 20) Pageable pageable) {

        return eventService.search(q, venueId, from, to, onlyAvailable, pageable);
    }

    @GetMapping("/{eventId}")
    public EventResponse getEvent(@PathVariable long eventId) {
        return eventService.get(eventId);
    }

    /**
     * Publishing is modelled as an action, not as a PATCH of the status field.
     *
     * <p>A status is not a value a client may set: it is the outcome of a
     * transition the server permits or refuses. Exposing it as a writable field
     * would invite {@code {"status": "CANCELLED"}} and make the state machine a
     * suggestion.
     */
    @PostMapping("/{eventId}/publish")
    public EventResponse publishEvent(@PathVariable long eventId) {
        return eventService.publish(eventId);
    }

    @PostMapping("/{eventId}/cancel")
    public EventResponse cancelEvent(@PathVariable long eventId) {
        return eventService.cancel(eventId);
    }

    @GetMapping("/{eventId}/seats")
    public EventSeatMapResponse getSeatMap(
            @PathVariable long eventId,
            @RequestParam(required = false) String section,
            @RequestParam(defaultValue = "false") boolean onlyAvailable) {
        return eventService.getSeatMap(eventId, section, onlyAvailable);
    }
}
