package io.github.david7777k.seatflow.catalog.web;

import io.github.david7777k.seatflow.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class EventControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private long venueId;

    @BeforeEach
    void createVenueWithSeats() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/venues").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Main Hall", "address": "Kyiv"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        venueId = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));

        // section A: 2 rows of 5 = 10 seats, section B: 1 row of 4 = 4 seats
        mockMvc.perform(post("/api/v1/venues/{id}/seats", venueId).with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sections": [
                                  {"name": "A", "rows": ["1", "2"], "seatsPerRow": 5},
                                  {"name": "B", "rows": ["1"],      "seatsPerRow": 4}
                                ]}
                                """))
                .andExpect(status().isCreated());
    }

    // --- creating ------------------------------------------------------------

    @Test
    void createsEventAsDraftAndMaterialisesEverySeat() throws Exception {
        mockMvc.perform(post("/api/v1/events").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("Spring Boot Conference",
                                "2026-11-01T18:00:00Z", "2026-11-01T21:00:00Z")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.venueName").value("Main Hall"))
                .andExpect(jsonPath("$.totalSeats").value(14))
                .andExpect(jsonPath("$.availableSeats").value(14));
    }

    @Test
    void appliesPerSectionPricesAndDefaultForTheRest() throws Exception {
        long eventId = createEvent("Priced", "2026-11-01T18:00:00Z", "2026-11-01T21:00:00Z");

        BigDecimal sectionA = jdbcTemplate.queryForObject("""
                select distinct es.price from event_seat es
                join seat s on s.id = es.seat_id
                where es.event_id = ? and s.section = 'A'
                """, BigDecimal.class, eventId);

        BigDecimal sectionB = jdbcTemplate.queryForObject("""
                select distinct es.price from event_seat es
                join seat s on s.id = es.seat_id
                where es.event_id = ? and s.section = 'B'
                """, BigDecimal.class, eventId);

        // section A is overridden to 750, section B falls back to defaultPrice 500
        assertThat(sectionA).isEqualByComparingTo("750.00");
        assertThat(sectionB).isEqualByComparingTo("500.00");
    }

    @Test
    void refusesEventAtVenueWithoutSeatMap() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/venues").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Empty Hall", "address": "Lviv"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        long emptyVenue = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));

        mockMvc.perform(post("/api/v1/events").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"venueId": %d, "title": "Nowhere",
                                 "startsAt": "2026-11-01T18:00:00Z", "endsAt": "2026-11-01T21:00:00Z",
                                 "salesStartAt": "2026-10-01T00:00:00Z", "salesEndAt": "2026-11-01T17:00:00Z",
                                 "defaultPrice": 100}
                                """.formatted(emptyVenue)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "Venue %d has no seat map, so it cannot host an event".formatted(emptyVenue)));
    }

    @Test
    void refusesEventForUnknownVenue() throws Exception {
        mockMvc.perform(post("/api/v1/events").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"venueId": 999, "title": "Ghost",
                                 "startsAt": "2026-11-01T18:00:00Z", "endsAt": "2026-11-01T21:00:00Z",
                                 "salesStartAt": "2026-10-01T00:00:00Z", "salesEndAt": "2026-11-01T17:00:00Z",
                                 "defaultPrice": 100}
                                """))
                .andExpect(status().isNotFound());
    }

    // --- the exclusion constraint doing its job -------------------------------

    @Test
    void refusesOverlappingEventAtSameVenue() throws Exception {
        createEvent("First", "2026-11-01T18:00:00Z", "2026-11-01T21:00:00Z");

        // starts while the first one is still running
        mockMvc.perform(post("/api/v1/events").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("Overlapping",
                                "2026-11-01T20:00:00Z", "2026-11-01T23:00:00Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("The venue already hosts an event during this time range"));
    }

    @Test
    void allowsBackToBackEventsAtSameVenue() throws Exception {
        createEvent("First", "2026-11-01T18:00:00Z", "2026-11-01T21:00:00Z");

        // the range is half-open, so starting exactly when the previous one ends
        // is not an overlap
        mockMvc.perform(post("/api/v1/events").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("Right after",
                                "2026-11-01T21:00:00Z", "2026-11-01T23:00:00Z")))
                .andExpect(status().isCreated());
    }

    @Test
    void freesTheSlotWhenTheOccupyingEventIsCancelled() throws Exception {
        long first = createEvent("First", "2026-11-01T18:00:00Z", "2026-11-01T21:00:00Z");

        mockMvc.perform(post("/api/v1/events/{id}/cancel", first).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // the constraint excludes cancelled events, so the slot is open again
        mockMvc.perform(post("/api/v1/events").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("Replacement",
                                "2026-11-01T19:00:00Z", "2026-11-01T22:00:00Z")))
                .andExpect(status().isCreated());
    }

    // --- lifecycle ------------------------------------------------------------

    @Test
    void publishesDraftEvent() throws Exception {
        long eventId = createEvent("To publish", "2026-11-01T18:00:00Z", "2026-11-01T21:00:00Z");

        mockMvc.perform(post("/api/v1/events/{id}/publish", eventId).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    void refusesToPublishTwice() throws Exception {
        long eventId = createEvent("Once", "2026-11-01T18:00:00Z", "2026-11-01T21:00:00Z");
        mockMvc.perform(post("/api/v1/events/{id}/publish", eventId).with(asAdmin())).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/events/{id}/publish", eventId).with(asAdmin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "Event %d cannot move from PUBLISHED to PUBLISHED".formatted(eventId)));
    }

    @Test
    void refusesToPublishCancelledEvent() throws Exception {
        long eventId = createEvent("Dead", "2026-11-01T18:00:00Z", "2026-11-01T21:00:00Z");
        mockMvc.perform(post("/api/v1/events/{id}/cancel", eventId).with(asAdmin())).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/events/{id}/publish", eventId).with(asAdmin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "Event %d cannot move from CANCELLED to PUBLISHED".formatted(eventId)));
    }

    @Test
    void cancelsPublishedEvent() throws Exception {
        long eventId = createEvent("Live", "2026-11-01T18:00:00Z", "2026-11-01T21:00:00Z");
        mockMvc.perform(post("/api/v1/events/{id}/publish", eventId).with(asAdmin())).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/events/{id}/cancel", eventId).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    // --- validation -----------------------------------------------------------

    @Test
    void rejectsEventEndingBeforeItStarts() throws Exception {
        mockMvc.perform(post("/api/v1/events").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("Backwards",
                                "2026-11-01T21:00:00Z", "2026-11-01T18:00:00Z")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.eventTimeRangeValid")
                        .value("endsAt must be after startsAt"));
    }

    @Test
    void rejectsSalesWindowOpeningAfterEventStarts() throws Exception {
        mockMvc.perform(post("/api/v1/events").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"venueId": %d, "title": "Too late",
                                 "startsAt": "2026-11-01T18:00:00Z", "endsAt": "2026-11-01T21:00:00Z",
                                 "salesStartAt": "2026-11-02T00:00:00Z", "salesEndAt": "2026-11-03T00:00:00Z",
                                 "defaultPrice": 100}
                                """.formatted(venueId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.salesWindowBeforeEvent")
                        .value("sales must open before the event starts"));
    }

    @Test
    void rejectsNegativePrice() throws Exception {
        mockMvc.perform(post("/api/v1/events").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"venueId": %d, "title": "Free money",
                                 "startsAt": "2026-11-01T18:00:00Z", "endsAt": "2026-11-01T21:00:00Z",
                                 "salesStartAt": "2026-10-01T00:00:00Z", "salesEndAt": "2026-11-01T17:00:00Z",
                                 "defaultPrice": -1}
                                """.formatted(venueId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.defaultPrice").value("defaultPrice must not be negative"));
    }

    @Test
    void returnsNotFoundForUnknownEvent() throws Exception {
        mockMvc.perform(get("/api/v1/events/999"))
                .andExpect(status().isNotFound());
    }

    // --- seat map -------------------------------------------------------------

    @Test
    void returnsSeatMapWithPricesAndStatuses() throws Exception {
        long eventId = createEvent("Mapped", "2026-11-01T18:00:00Z", "2026-11-01T21:00:00Z");

        mockMvc.perform(get("/api/v1/events/{id}/seats", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnedSeats").value(14))
                .andExpect(jsonPath("$.availableSeats").value(14))
                .andExpect(jsonPath("$.seats", hasSize(14)))
                .andExpect(jsonPath("$.seats[0].section").value("A"))
                .andExpect(jsonPath("$.seats[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.seats[0].price").value(750.00));
    }

    @Test
    void filtersSeatMapBySection() throws Exception {
        long eventId = createEvent("Mapped", "2026-11-01T18:00:00Z", "2026-11-01T21:00:00Z");

        mockMvc.perform(get("/api/v1/events/{id}/seats", eventId).param("section", "B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.returnedSeats").value(4))
                .andExpect(jsonPath("$.seats[0].price").value(500.00));
    }

    // --- helpers --------------------------------------------------------------

    private String eventJson(String title, String startsAt, String endsAt) {
        return """
                {"venueId": %d, "title": "%s", "description": "Talks about backend engineering",
                 "startsAt": "%s", "endsAt": "%s",
                 "salesStartAt": "2026-10-01T00:00:00Z", "salesEndAt": "2026-10-31T00:00:00Z",
                 "defaultPrice": 500, "sectionPrices": {"A": 750}}
                """.formatted(venueId, title, startsAt, endsAt);
    }

    private long createEvent(String title, String startsAt, String endsAt) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/events").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson(title, startsAt, endsAt)))
                .andExpect(status().isCreated())
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }
}
