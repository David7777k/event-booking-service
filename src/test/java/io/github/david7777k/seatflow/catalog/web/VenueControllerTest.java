package io.github.david7777k.seatflow.catalog.web;

import io.github.david7777k.seatflow.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class VenueControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // --- creating a venue ---------------------------------------------------

    @Test
    void createsVenueAndReturnsItsLocation() throws Exception {
        mockMvc.perform(post("/api/v1/venues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Main Hall", "address": "Kyiv, Khreshchatyk 1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/venues/1"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Main Hall"))
                .andExpect(jsonPath("$.seatCount").value(0));
    }

    @Test
    void rejectsVenueWithBlankName() throws Exception {
        mockMvc.perform(post("/api/v1/venues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "   ", "address": "Kyiv"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors.name").value("name must not be blank"));
    }

    @Test
    void rejectsVenueWithMissingAddress() throws Exception {
        mockMvc.perform(post("/api/v1/venues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Main Hall"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.address").exists());
    }

    // --- reading a venue ----------------------------------------------------

    @Test
    void returnsNotFoundForUnknownVenue() throws Exception {
        mockMvc.perform(get("/api/v1/venues/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource not found"))
                .andExpect(jsonPath("$.detail").value("Venue 999 not found"));
    }

    // --- seat maps ----------------------------------------------------------

    @Test
    void createsSeatMapWithExpectedNumberOfSeats() throws Exception {
        long venueId = createVenue("Main Hall");

        // 2 sections: A has 3 rows of 10, B has 2 rows of 5 => 30 + 10 = 40
        mockMvc.perform(post("/api/v1/venues/{id}/seats", venueId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sections": [
                                  {"name": "A", "rows": ["1", "2", "3"], "seatsPerRow": 10},
                                  {"name": "B", "rows": ["1", "2"],      "seatsPerRow": 5}
                                ]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalSeats").value(40))
                .andExpect(jsonPath("$.seats", hasSize(40)))
                .andExpect(jsonPath("$.seats[0].section").value("A"))
                .andExpect(jsonPath("$.seats[0].rowLabel").value("1"))
                .andExpect(jsonPath("$.seats[0].seatNumber").value(1));

        Integer stored = jdbcTemplate.queryForObject(
                "select count(*) from seat where venue_id = ?", Integer.class, venueId);
        assertThat(stored).isEqualTo(40);
    }

    @Test
    void refusesToCreateSecondSeatMapForSameVenue() throws Exception {
        long venueId = createVenue("Main Hall");
        createSeatMap(venueId);

        mockMvc.perform(post("/api/v1/venues/{id}/seats", venueId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sections": [{"name": "A", "rows": ["1"], "seatsPerRow": 2}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Venue %d already has a seat map".formatted(venueId)));
    }

    @Test
    void rejectsSeatMapWithNoSections() throws Exception {
        long venueId = createVenue("Main Hall");

        mockMvc.perform(post("/api/v1/venues/{id}/seats", venueId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sections": []}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.sections").value("at least one section is required"));
    }

    @Test
    void rejectsSeatMapWithSeatsPerRowAboveLimit() throws Exception {
        long venueId = createVenue("Main Hall");

        mockMvc.perform(post("/api/v1/venues/{id}/seats", venueId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sections": [{"name": "A", "rows": ["1"], "seatsPerRow": 5000}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['sections[0].seatsPerRow']")
                        .value("seatsPerRow must be at most 200"));
    }

    @Test
    void refusesSeatMapForUnknownVenue() throws Exception {
        mockMvc.perform(post("/api/v1/venues/{id}/seats", 999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sections": [{"name": "A", "rows": ["1"], "seatsPerRow": 2}]}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void filtersSeatMapBySection() throws Exception {
        long venueId = createVenue("Main Hall");

        mockMvc.perform(post("/api/v1/venues/{id}/seats", venueId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sections": [
                                  {"name": "A", "rows": ["1"], "seatsPerRow": 4},
                                  {"name": "B", "rows": ["1"], "seatsPerRow": 6}
                                ]}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/venues/{id}/seats", venueId).param("section", "B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSeats").value(6))
                .andExpect(jsonPath("$.seats[0].section").value("B"));
    }

    // --- listing ------------------------------------------------------------

    @Test
    void listsVenuesWithTheirSeatCounts() throws Exception {
        long withSeats = createVenue("A Hall");
        createSeatMap(withSeats);
        createVenue("B Hall");

        mockMvc.perform(get("/api/v1/venues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].name").value("A Hall"))
                .andExpect(jsonPath("$.content[0].seatCount").value(6))
                .andExpect(jsonPath("$.content[1].name").value("B Hall"))
                .andExpect(jsonPath("$.content[1].seatCount").value(0));
    }

    @Test
    void cappsPageSizeAtConfiguredMaximum() throws Exception {
        createVenue("Main Hall");

        mockMvc.perform(get("/api/v1/venues").param("size", "100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(100));
    }

    // --- helpers ------------------------------------------------------------

    private long createVenue(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/venues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "address": "Kyiv"}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    private void createSeatMap(long venueId) throws Exception {
        mockMvc.perform(post("/api/v1/venues/{id}/seats", venueId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sections": [{"name": "A", "rows": ["1"], "seatsPerRow": 6}]}
                                """))
                .andExpect(status().isCreated());
    }
}
