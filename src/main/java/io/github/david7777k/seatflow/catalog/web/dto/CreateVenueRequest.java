package io.github.david7777k.seatflow.catalog.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateVenueRequest(

        @NotBlank(message = "name must not be blank")
        @Size(max = 200, message = "name must be at most 200 characters")
        String name,

        @NotBlank(message = "address must not be blank")
        @Size(max = 500, message = "address must be at most 500 characters")
        String address) {
}
