package io.github.david7777k.seatflow.catalog.service;

import io.github.david7777k.seatflow.catalog.domain.Seat;
import io.github.david7777k.seatflow.catalog.domain.Venue;
import io.github.david7777k.seatflow.catalog.repository.SeatBatchWriter;
import io.github.david7777k.seatflow.catalog.repository.SeatRepository;
import io.github.david7777k.seatflow.catalog.repository.VenueRepository;
import io.github.david7777k.seatflow.catalog.web.dto.CreateSeatMapRequest;
import io.github.david7777k.seatflow.catalog.web.dto.CreateVenueRequest;
import io.github.david7777k.seatflow.catalog.web.dto.SeatMapResponse;
import io.github.david7777k.seatflow.catalog.web.dto.SeatResponse;
import io.github.david7777k.seatflow.catalog.web.dto.VenueResponse;
import io.github.david7777k.seatflow.common.error.ConflictException;
import io.github.david7777k.seatflow.common.error.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class VenueService {

    private static final Logger log = LoggerFactory.getLogger(VenueService.class);

    private final VenueRepository venueRepository;
    private final SeatRepository seatRepository;
    private final SeatBatchWriter seatBatchWriter;

    public VenueService(VenueRepository venueRepository,
                        SeatRepository seatRepository,
                        SeatBatchWriter seatBatchWriter) {
        this.venueRepository = venueRepository;
        this.seatRepository = seatRepository;
        this.seatBatchWriter = seatBatchWriter;
    }

    @Transactional
    public VenueResponse create(CreateVenueRequest request) {
        Venue venue = venueRepository.save(new Venue(request.name(), request.address()));
        log.debug("Created venue {}", venue.getId());
        return VenueResponse.from(venue, 0);
    }

    @Transactional(readOnly = true)
    public VenueResponse get(long venueId) {
        Venue venue = requireVenue(venueId);
        return VenueResponse.from(venue, seatRepository.countByVenueId(venueId));
    }

    /**
     * Seat counts come from the same query as the venues. Counting per venue in
     * a loop would be the textbook N+1: one query for the page, then one more
     * for every row on it.
     */
    @Transactional(readOnly = true)
    public Page<VenueResponse> list(Pageable pageable) {
        return venueRepository.findAllWithSeatCount(pageable);
    }

    /**
     * Creates the seat map in one batched insert.
     *
     * <p>The "already has a seat map" check is not the real guarantee — two
     * concurrent calls could both pass it. The unique constraint on
     * (venue_id, section, row_label, seat_number) is what actually prevents a
     * duplicated map; this check exists so the ordinary case gets a clear 409
     * rather than a constraint violation.
     */
    @Transactional
    public SeatMapResponse createSeatMap(long venueId, CreateSeatMapRequest request) {
        requireVenue(venueId);

        if (seatRepository.existsByVenueId(venueId)) {
            throw new ConflictException("Venue %d already has a seat map".formatted(venueId));
        }

        List<SeatBatchWriter.SeatRow> rows = new ArrayList<>();
        for (CreateSeatMapRequest.SectionSpec section : request.sections()) {
            for (String rowLabel : section.rows()) {
                for (int number = 1; number <= section.seatsPerRow(); number++) {
                    rows.add(new SeatBatchWriter.SeatRow(venueId, section.name(), rowLabel, number));
                }
            }
        }

        seatBatchWriter.insertAll(rows);
        log.debug("Created {} seats for venue {}", rows.size(), venueId);

        return getSeatMap(venueId, null);
    }

    @Transactional(readOnly = true)
    public SeatMapResponse getSeatMap(long venueId, String section) {
        requireVenue(venueId);

        List<Seat> seats = seatRepository.findSeatMap(venueId, section);
        List<SeatResponse> payload = seats.stream().map(SeatResponse::from).toList();

        return new SeatMapResponse(venueId, payload.size(), payload);
    }

    private Venue requireVenue(long venueId) {
        return venueRepository.findById(venueId)
                .orElseThrow(() -> new ResourceNotFoundException("Venue", venueId));
    }
}
