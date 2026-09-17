package io.github.david7777k.seatflow.catalog.web;

import io.github.david7777k.seatflow.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class EventSearchTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private long kyivVenue;
    private long lvivVenue;

    @BeforeEach
    void seedCatalogue() throws Exception {
        kyivVenue = createVenueWithSeats("Kyiv Hall");
        lvivVenue = createVenueWithSeats("Lviv Hall");

        publish(createEvent(kyivVenue, "Spring Boot Conference",
                "Deep dive into backend engineering and databases",
                "2026-11-01T18:00:00Z", "2026-11-01T21:00:00Z"));

        publish(createEvent(kyivVenue, "Jazz Evening",
                "A quiet night of live music",
                "2026-12-05T19:00:00Z", "2026-12-05T22:00:00Z"));

        publish(createEvent(lvivVenue, "Database Internals Workshop",
                "How a query planner actually works",
                "2026-11-20T10:00:00Z", "2026-11-20T17:00:00Z"));

        // left as DRAFT on purpose: must never appear in search results
        createEvent(lvivVenue, "Secret Rehearsal", "Not announced yet",
                "2026-12-20T10:00:00Z", "2026-12-20T12:00:00Z");
    }

    // --- text search ---------------------------------------------------------

    @Test
    void findsEventsByWordInTitle() throws Exception {
        mockMvc.perform(get("/api/v1/events").param("q", "jazz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].title").value("Jazz Evening"));
    }

    @Test
    void findsEventsByWordInDescription() throws Exception {
        mockMvc.perform(get("/api/v1/events").param("q", "planner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].title").value("Database Internals Workshop"));
    }

    @Test
    void matchesDifferentFormsOfTheSameWord() throws Exception {
        // "databases" appears in a description; the stemmer reduces both the
        // stored text and the query to the same lexeme, which LIKE could not do
        mockMvc.perform(get("/api/v1/events").param("q", "database"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)));
    }

    @Test
    void ranksTitleMatchesAboveDescriptionMatches() throws Exception {
        // "Database Internals Workshop" has the term in its title (weight A),
        // "Spring Boot Conference" only in its description (weight B)
        mockMvc.perform(get("/api/v1/events").param("q", "database"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Database Internals Workshop"))
                .andExpect(jsonPath("$.content[1].title").value("Spring Boot Conference"));
    }

    @Test
    void returnsNothingForTermThatMatchesNoEvent() throws Exception {
        mockMvc.perform(get("/api/v1/events").param("q", "skydiving"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    void treatsBlankQueryAsNoQuery() throws Exception {
        mockMvc.perform(get("/api/v1/events").param("q", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)));
    }

    // --- visibility ----------------------------------------------------------

    @Test
    void neverReturnsUnpublishedEvents() throws Exception {
        mockMvc.perform(get("/api/v1/events").param("q", "rehearsal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    void listsAllPublishedEventsOrderedByStartTimeWhenNoQueryGiven() throws Exception {
        mockMvc.perform(get("/api/v1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.content[0].title").value("Spring Boot Conference"))
                .andExpect(jsonPath("$.content[1].title").value("Database Internals Workshop"))
                .andExpect(jsonPath("$.content[2].title").value("Jazz Evening"));
    }

    // --- filters -------------------------------------------------------------

    @Test
    void filtersByVenue() throws Exception {
        mockMvc.perform(get("/api/v1/events").param("venueId", String.valueOf(lvivVenue)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].venueName").value("Lviv Hall"));
    }

    @Test
    void filtersByDateRange() throws Exception {
        mockMvc.perform(get("/api/v1/events")
                        .param("from", "2026-11-15T00:00:00Z")
                        .param("to", "2026-12-10T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].title").value("Database Internals Workshop"));
    }

    @Test
    void combinesTextSearchWithVenueFilter() throws Exception {
        mockMvc.perform(get("/api/v1/events")
                        .param("q", "database")
                        .param("venueId", String.valueOf(kyivVenue)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].title").value("Spring Boot Conference"));
    }

    @Test
    void reportsAvailableSeatsPerEvent() throws Exception {
        mockMvc.perform(get("/api/v1/events").param("q", "jazz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].availableSeats").value(6));
    }

    @Test
    void excludesSoldOutEventsWhenOnlyAvailableRequested() throws Exception {
        // A booked seat must reference a real booking: the foreign key and the
        // event_seat_booking_consistency check both say so, so selling out has
        // to be expressed properly rather than faked with a dummy id.
        Long userId = jdbcTemplate.queryForObject("""
                insert into app_user (email, password_hash)
                values ('buyer@example.com', 'not-a-real-hash')
                returning id
                """, Long.class);

        Long bookingId = jdbcTemplate.queryForObject("""
                insert into booking (event_id, user_id, status, total_amount, confirmed_at)
                select id, ?, 'CONFIRMED', 0, now() from event where title = 'Jazz Evening'
                returning id
                """, Long.class, userId);

        jdbcTemplate.update("""
                update event_seat set status = 'BOOKED', booking_id = ?
                where event_id = (select id from event where title = 'Jazz Evening')
                """, bookingId);

        mockMvc.perform(get("/api/v1/events").param("onlyAvailable", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)));

        mockMvc.perform(get("/api/v1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)));
    }

    // --- pagination ----------------------------------------------------------

    @Test
    void paginatesResults() throws Exception {
        mockMvc.perform(get("/api/v1/events").param("size", "2").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(2));

        mockMvc.perform(get("/api/v1/events").param("size", "2").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    void ignoresCallerSuppliedSortBecauseOrderingIsPartOfTheQuery() throws Exception {
        // a sort parameter must not be appended to a native query that already
        // orders its rows
        mockMvc.perform(get("/api/v1/events").param("sort", "title,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Spring Boot Conference"));
    }

    // --- the index is actually used ------------------------------------------

    @Test
    void textSearchCanBeServedByTheGinIndex() {
        // Every functional test above would pass just as happily with the table
        // scanned sequentially, so the index needs checking separately.
        //
        // What is asserted here is that the index is *applicable* to the query,
        // not that the planner picks it. On a few thousand narrow rows a
        // sequential scan genuinely is cheaper and choosing it is correct;
        // asserting otherwise would be asserting that the planner is wrong.
        // Sequential scans are therefore penalised, and the question becomes:
        // when an index has to be used, is ours the one that fits?
        //
        // Rows get non-overlapping hourly slots so the exclusion constraint
        // accepts them, and the table is analysed so the planner has real
        // statistics rather than defaults.
        jdbcTemplate.update("""
                insert into event (venue_id, title, description,
                                   starts_at, ends_at, sales_start_at, sales_end_at, status)
                select ?,
                       'Filler event ' || g,
                       'Assorted programme of music, theatre and dance',
                       timestamptz '2027-01-01 00:00:00' + make_interval(hours => g),
                       timestamptz '2027-01-01 00:30:00' + make_interval(hours => g),
                       timestamptz '2026-10-01 00:00:00',
                       timestamptz '2026-10-31 00:00:00',
                       'PUBLISHED'
                from generate_series(1, 5000) g
                """, kyivVenue);
        jdbcTemplate.execute("analyze event");
        jdbcTemplate.execute("set enable_seqscan = off");

        String plan;
        try {
            // Only the text predicate. Including `status = 'PUBLISHED'` gives
            // the planner a second option - the partial index on published
            // events - and it picks that one, which says nothing about whether
            // the tsvector predicate is indexable.
            plan = String.join("\n", jdbcTemplate.queryForList("""
                    explain
                    select e.id from event e
                    where e.search_vector @@ plainto_tsquery('english', 'database')
                    """, String.class));
        } finally {
            jdbcTemplate.execute("reset enable_seqscan");
        }

        assertThat(plan)
                .as("full-text search must be answerable from the GIN index, plan was:%n%s", plan)
                .contains("event_search_idx");
    }

    // --- helpers -------------------------------------------------------------

    private long createVenueWithSeats(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/venues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "address": "Ukraine"}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        long venueId = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));

        mockMvc.perform(post("/api/v1/venues/{id}/seats", venueId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sections": [{"name": "A", "rows": ["1"], "seatsPerRow": 6}]}
                                """))
                .andExpect(status().isCreated());

        return venueId;
    }

    private long createEvent(long venueId, String title, String description,
                             String startsAt, String endsAt) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"venueId": %d, "title": "%s", "description": "%s",
                                 "startsAt": "%s", "endsAt": "%s",
                                 "salesStartAt": "2026-10-01T00:00:00Z",
                                 "salesEndAt": "2026-10-31T00:00:00Z",
                                 "defaultPrice": 500}
                                """.formatted(venueId, title, description, startsAt, endsAt)))
                .andExpect(status().isCreated())
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    private void publish(long eventId) throws Exception {
        mockMvc.perform(post("/api/v1/events/{id}/publish", eventId))
                .andExpect(status().isOk());
    }
}
