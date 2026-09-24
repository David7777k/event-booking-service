package io.github.david7777k.seatflow.security.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Not reusing RegisterRequest: its length rules would reject a login before
 * checking it, revealing which passwords cannot exist.
 */
public record LoginRequest(

        @NotBlank(message = "email must not be blank")
        String email,

        @NotBlank(message = "password must not be blank")
        String password) {
}
