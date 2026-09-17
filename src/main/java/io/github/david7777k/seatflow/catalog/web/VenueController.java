package io.github.david7777k.seatflow.catalog.web;

import io.github.david7777k.seatflow.catalog.service.VenueService;
import io.github.david7777k.seatflow.catalog.web.dto.CreateSeatMapRequest;
import io.github.david7777k.seatflow.catalog.web.dto.CreateVenueRequest;
import io.github.david7777k.seatflow.catalog.web.dto.SeatMapResponse;
import io.github.david7777k.seatflow.catalog.web.dto.VenueResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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

@RestController
@RequestMapping("/api/v1/venues")
public class VenueController {

    private final VenueService venueService;

    public VenueController(VenueService venueService) {
        this.venueService = venueService;
    }

    /**
     * 201 with a Location header: the client asked for a resource to be created
     * and is told where it now lives.
     */
    @PostMapping
    public ResponseEntity<VenueResponse> createVenue(@Valid @RequestBody CreateVenueRequest request) {
        VenueResponse venue = venueService.create(request);

        URI location = UriComponentsBuilder.fromPath("/api/v1/venues/{id}")
                .buildAndExpand(venue.id())
                .toUri();

        return ResponseEntity.created(location).body(venue);
    }

    @GetMapping("/{venueId}")
    public VenueResponse getVenue(@PathVariable long venueId) {
        return venueService.get(venueId);
    }

    @GetMapping
    public Page<VenueResponse> listVenues(
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        return venueService.list(pageable);
    }

    @PostMapping("/{venueId}/seats")
    public ResponseEntity<SeatMapResponse> createSeatMap(
            @PathVariable long venueId,
            @Valid @RequestBody CreateSeatMapRequest request) {

        SeatMapResponse seatMap = venueService.createSeatMap(venueId, request);

        URI location = UriComponentsBuilder.fromPath("/api/v1/venues/{id}/seats")
                .buildAndExpand(venueId)
                .toUri();

        return ResponseEntity.created(location).body(seatMap);
    }

    @GetMapping("/{venueId}/seats")
    public SeatMapResponse getSeatMap(
            @PathVariable long venueId,
            @RequestParam(required = false) String section) {
        return venueService.getSeatMap(venueId, section);
    }
}
