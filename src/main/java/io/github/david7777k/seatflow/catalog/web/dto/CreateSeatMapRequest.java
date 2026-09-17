package io.github.david7777k.seatflow.catalog.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Describes a seat map as sections of rows rather than as a list of individual
 * seats: a 600-seat hall is a handful of lines instead of a 600-element array.
 */
public record CreateSeatMapRequest(

        @NotEmpty(message = "at least one section is required")
        @Size(max = 50, message = "at most 50 sections are allowed")
        @Valid
        List<SectionSpec> sections) {

    public record SectionSpec(

            @NotBlank(message = "section name must not be blank")
            @Size(max = 50, message = "section name must be at most 50 characters")
            String name,

            @NotEmpty(message = "at least one row is required")
            @Size(max = 100, message = "at most 100 rows are allowed")
            List<@NotBlank(message = "row label must not be blank") String> rows,

            // Bounded so a single request cannot ask the service to write an
            // unbounded number of rows.
            @Min(value = 1, message = "seatsPerRow must be at least 1")
            @Max(value = 200, message = "seatsPerRow must be at most 200")
            int seatsPerRow) {
    }
}
