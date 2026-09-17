package io.github.david7777k.seatflow.catalog.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Bulk-inserts seats with a single batched statement.
 *
 * <p>Why not {@code SeatRepository.saveAll}: the schema uses
 * {@code GENERATED ALWAYS AS IDENTITY}, which maps to
 * {@code GenerationType.IDENTITY}. Hibernate cannot batch inserts for an
 * identity column - it must execute each insert on its own to read back the
 * generated key before the entity is managed. Creating a 600-seat venue would
 * therefore cost 600 round trips.
 *
 * <p>Nothing in the seat map needs to be a managed entity at creation time, so
 * this path goes straight to JDBC and inserts every seat in one batch.
 */
@Repository
public class SeatBatchWriter {

    private static final String INSERT_SEAT = """
            insert into seat (venue_id, section, row_label, seat_number)
            values (?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public SeatBatchWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record SeatRow(long venueId, String section, String rowLabel, int seatNumber) {
    }

    public void insertAll(List<SeatRow> seats) {
        jdbcTemplate.batchUpdate(INSERT_SEAT, seats, seats.size(), (ps, seat) -> {
            ps.setLong(1, seat.venueId());
            ps.setString(2, seat.section());
            ps.setString(3, seat.rowLabel());
            ps.setInt(4, seat.seatNumber());
        });
    }
}
