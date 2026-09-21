package io.github.david7777k.seatflow.booking;

import io.github.david7777k.seatflow.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One user must not be able to see or touch another user's bookings.
 *
 * <p>Until issue #9 the caller announced its own user id in the request body,
 * so every one of these cases would have succeeded. These tests exist to make
 * sure that cannot come back.
 */
@AutoConfigureMockMvc
class BookingOwnershipTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private long owner;
    private long stranger;
    private long bookingId;
    private List<Long> seatIds;

    @BeforeEach
    void seedBookingOwnedByOneUser() throws Exception {
        owner = createUser("owner@example.com");
        stranger = createUser("stranger@example.com");

        long venueId = createVenue();
        long eventId = createEvent(venueId);
        mockMvc.perform(post("/api/v1/events/{id}/publish", eventId).with(asAdmin()))
                .andExpect(status().isOk());

        seatIds = jdbcTemplate.queryForList(
                "select id from event_seat where event_id = ? order by id", Long.class, eventId);

        MvcResult result = mockMvc.perform(post("/api/v1/bookings").with(asUser(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId": %d, "seatIds": [%d]}
                                """.formatted(eventId, seatIds.get(0))))
                .andExpect(status().isCreated())
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        bookingId = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }

    @Test
    void ownerCanReadTheirOwnBooking() throws Exception {
        mockMvc.perform(get("/api/v1/bookings/{id}", bookingId).with(asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(owner));
    }

    @Test
    void strangerCannotReadSomebodyElsesBooking() throws Exception {
        // 404 rather than 403 on purpose: a 403 would confirm that a booking
        // with this id exists, which is itself information the stranger has no
        // business having.
        mockMvc.perform(get("/api/v1/bookings/{id}", bookingId).with(asUser(stranger)))
                .andExpect(status().isNotFound());
    }

    @Test
    void strangerCannotConfirmSomebodyElsesBooking() throws Exception {
        mockMvc.perform(post("/api/v1/bookings/{id}/confirm", bookingId).with(asUser(stranger)))
                .andExpect(status().isNotFound());
    }

    @Test
    void strangerCannotCancelSomebodyElsesBooking() throws Exception {
        mockMvc.perform(delete("/api/v1/bookings/{id}", bookingId).with(asUser(stranger)))
                .andExpect(status().isNotFound());
    }

    @Test
    void listingOnlyEverShowsTheCallersOwnBookings() throws Exception {
        mockMvc.perform(get("/api/v1/bookings").with(asUser(stranger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));

        mockMvc.perform(get("/api/v1/bookings").with(asUser(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    void administratorCanReadAnyBooking() throws Exception {
        mockMvc.perform(get("/api/v1/bookings/{id}", bookingId).with(asAdmin(stranger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(owner));
    }

    @Test
    void administratorCannotConfirmOnSomebodyElsesBehalf() throws Exception {
        // Confirming is an act of purchase. Being an administrator is not a
        // reason to be able to buy something for another person.
        mockMvc.perform(post("/api/v1/bookings/{id}/confirm", bookingId).with(asAdmin(stranger)))
                .andExpect(status().isNotFound());
    }

    // --- helpers --------------------------------------------------------------

    private long createVenue() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/venues").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Hall", "address": "Kyiv"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        long venueId = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));

        mockMvc.perform(post("/api/v1/venues/{id}/seats", venueId).with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sections": [{"name": "A", "rows": ["1"], "seatsPerRow": 4}]}
                                """))
                .andExpect(status().isCreated());

        return venueId;
    }

    private long createEvent(long venueId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/events").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"venueId": %d, "title": "Concert", "description": "Live music",
                                 "startsAt": "2027-11-01T18:00:00Z", "endsAt": "2027-11-01T21:00:00Z",
                                 "salesStartAt": "2020-01-01T00:00:00Z",
                                 "salesEndAt": "2030-01-01T00:00:00Z",
                                 "defaultPrice": 500}
                                """.formatted(venueId)))
                .andExpect(status().isCreated())
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        return Long.parseLong(location.substring(location.lastIndexOf('/') + 1));
    }
}
