package io.github.david7777k.seatflow.catalog.service;

import io.github.david7777k.seatflow.catalog.domain.Event;
import io.github.david7777k.seatflow.catalog.domain.EventSeat;
import io.github.david7777k.seatflow.catalog.domain.EventStatus;
import io.github.david7777k.seatflow.catalog.domain.SeatStatus;
import io.github.david7777k.seatflow.catalog.domain.Venue;
import io.github.david7777k.seatflow.catalog.repository.EventRepository;
import io.github.david7777k.seatflow.catalog.repository.EventSeatBatchWriter;
import io.github.david7777k.seatflow.catalog.repository.EventSeatRepository;
import io.github.david7777k.seatflow.catalog.repository.SeatRepository;
import io.github.david7777k.seatflow.catalog.repository.VenueRepository;
import io.github.david7777k.seatflow.catalog.web.dto.CreateEventRequest;
import io.github.david7777k.seatflow.catalog.web.dto.EventResponse;
import io.github.david7777k.seatflow.catalog.web.dto.EventSeatMapResponse;
import io.github.david7777k.seatflow.catalog.web.dto.EventSeatResponse;
import io.github.david7777k.seatflow.common.error.ConflictException;
import io.github.david7777k.seatflow.common.error.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository eventRepository;
    private final EventSeatRepository eventSeatRepository;
    private final EventSeatBatchWriter eventSeatBatchWriter;
    private final VenueRepository venueRepository;
    private final SeatRepository seatRepository;

    public EventService(EventRepository eventRepository,
                        EventSeatRepository eventSeatRepository,
                        EventSeatBatchWriter eventSeatBatchWriter,
                        VenueRepository venueRepository,
                        SeatRepository seatRepository) {
        this.eventRepository = eventRepository;
        this.eventSeatRepository = eventSeatRepository;
        this.eventSeatBatchWriter = eventSeatBatchWriter;
        this.venueRepository = venueRepository;
        this.seatRepository = seatRepository;
    }

    /**
     * Creates an event and materialises one priced row per seat of its venue.
     *
     * <p>Two events cannot occupy the same venue at overlapping times. That is
     * not checked here: the exclusion constraint on the table rejects it. A
     * read-then-write check in this method would be racy in exactly the way
     * issue #7 is about, and would be redundant once the constraint exists.
     */
    @Transactional
    public EventResponse create(CreateEventRequest request) {
        Venue venue = venueRepository.findById(request.venueId())
                .orElseThrow(() -> new ResourceNotFoundException("Venue", request.venueId()));

        if (!seatRepository.existsByVenueId(venue.getId())) {
            throw new ConflictException(
                    "Venue %d has no seat map, so it cannot host an event".formatted(venue.getId()));
        }

        Event event = new Event(venue, request.title(), request.description(),
                request.startsAt(), request.endsAt(),
                request.salesStartAt(), request.salesEndAt());

        // Flushed rather than merely saved, for two reasons: the seats below are
        // written with plain JDBC and need the id to exist in the database, and
        // a constraint violation surfaces here instead of at commit time, where
        // it could no longer be attributed to this operation.
        eventRepository.saveAndFlush(event);

        int seats = eventSeatBatchWriter.materialise(
                event.getId(), venue.getId(), request.defaultPrice(), request.sectionPrices());

        log.debug("Created event {} at venue {} with {} seats", event.getId(), venue.getId(), seats);

        return EventResponse.from(event, seats, seats);
    }

    @Transactional(readOnly = true)
    public EventResponse get(long eventId) {
        return toResponse(requireEvent(eventId));
    }

    /**
     * Puts the event on sale.
     *
     * <p>No explicit save: the entity is managed inside this transaction, so
     * Hibernate's dirty checking writes the change at flush. The transition
     * itself is validated by the entity, which is what stops a caller from
     * assigning an arbitrary status.
     */
    @Transactional
    public EventResponse publish(long eventId) {
        Event event = requireEvent(eventId);

        if (!eventSeatRepository.existsByEventId(eventId)) {
            throw new ConflictException("Event %d has no seats and cannot be published".formatted(eventId));
        }

        event.transitionTo(EventStatus.PUBLISHED);
        log.debug("Published event {}", eventId);

        return toResponse(event);
    }

    @Transactional
    public EventResponse cancel(long eventId) {
        Event event = requireEvent(eventId);
        event.transitionTo(EventStatus.CANCELLED);
        log.debug("Cancelled event {}", eventId);

        return toResponse(event);
    }

    @Transactional(readOnly = true)
    public EventSeatMapResponse getSeatMap(long eventId, String section, boolean onlyAvailable) {
        requireEvent(eventId);

        List<EventSeat> seats = eventSeatRepository.findSeatMap(eventId, section, onlyAvailable);
        List<EventSeatResponse> payload = seats.stream().map(EventSeatResponse::from).toList();

        return new EventSeatMapResponse(
                eventId,
                payload.size(),
                eventSeatRepository.countByEventIdAndStatus(eventId, SeatStatus.AVAILABLE),
                payload);
    }

    private Event requireEvent(long eventId) {
        return eventRepository.findByIdWithVenue(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", eventId));
    }

    private EventResponse toResponse(Event event) {
        return EventResponse.from(
                event,
                eventSeatRepository.countByEventId(event.getId()),
                eventSeatRepository.countByEventIdAndStatus(event.getId(), SeatStatus.AVAILABLE));
    }
}
