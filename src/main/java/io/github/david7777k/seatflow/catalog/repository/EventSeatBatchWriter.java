package io.github.david7777k.seatflow.catalog.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Materialises the seats of an event.
 *
 * <p>Creating an event turns every seat of its venue into a priced, bookable
 * row. That work never leaves the database: seats are not read into Java only
 * to be written straight back. A 600-seat venue costs two statements rather
 * than 600 inserts.
 */
@Repository
public class EventSeatBatchWriter {

    private static final String MATERIALISE_SEATS = """
            insert into event_seat (event_id, seat_id, price)
            select ?, s.id, ?
            from seat s
            where s.venue_id = ?
            """;

    private static final String OVERRIDE_SECTION_PRICE = """
            update event_seat es
            set price = ?
            from seat s
            where s.id = es.seat_id
              and es.event_id = ?
              and s.section = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public EventSeatBatchWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @return how many seats the event now offers
     */
    public int materialise(long eventId, long venueId,
                           BigDecimal defaultPrice, Map<String, BigDecimal> sectionPrices) {

        int created = jdbcTemplate.update(MATERIALISE_SEATS, eventId, defaultPrice, venueId);

        sectionPrices.forEach((section, price) ->
                jdbcTemplate.update(OVERRIDE_SECTION_PRICE, price, eventId, section));

        return created;
    }
}
