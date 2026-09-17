package io.github.david7777k.seatflow.catalog.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record CreateEventRequest(

        @NotNull(message = "venueId is required")
        Long venueId,

        @NotBlank(message = "title must not be blank")
        @Size(max = 300, message = "title must be at most 300 characters")
        String title,

        @Size(max = 5000, message = "description must be at most 5000 characters")
        String description,

        @NotNull(message = "startsAt is required")
        Instant startsAt,

        @NotNull(message = "endsAt is required")
        Instant endsAt,

        @NotNull(message = "salesStartAt is required")
        Instant salesStartAt,

        @NotNull(message = "salesEndAt is required")
        Instant salesEndAt,

        @NotNull(message = "defaultPrice is required")
        @DecimalMin(value = "0.0", message = "defaultPrice must not be negative")
        @Digits(integer = 10, fraction = 2, message = "defaultPrice must have at most 2 decimal places")
        BigDecimal defaultPrice,

        /** Per-section overrides. Sections not listed here use defaultPrice. */
        Map<String, @DecimalMin(value = "0.0", message = "section price must not be negative") BigDecimal> sectionPrices) {

    public CreateEventRequest {
        description = description == null ? "" : description;
        sectionPrices = sectionPrices == null ? Map.of() : Map.copyOf(sectionPrices);
    }

    /**
     * Cross-field rules live here rather than in the service so a malformed
     * request is rejected with 400 and a field message, before any work starts.
     *
     * <p>The database enforces the same rules with CHECK constraints. That is
     * not duplication for its own sake: validation exists to give a useful
     * error, the constraint exists so the rule cannot be bypassed.
     */
    @JsonIgnore
    @AssertTrue(message = "endsAt must be after startsAt")
    public boolean isEventTimeRangeValid() {
        return startsAt == null || endsAt == null || endsAt.isAfter(startsAt);
    }

    @JsonIgnore
    @AssertTrue(message = "salesEndAt must be after salesStartAt")
    public boolean isSalesWindowValid() {
        return salesStartAt == null || salesEndAt == null || salesEndAt.isAfter(salesStartAt);
    }

    @JsonIgnore
    @AssertTrue(message = "sales must open before the event starts")
    public boolean isSalesWindowBeforeEvent() {
        return salesStartAt == null || startsAt == null || salesStartAt.isBefore(startsAt);
    }
}
