package io.github.david7777k.seatflow.security.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Login deliberately does not reuse {@link RegisterRequest}.
 *
 * <p>Applying the registration rules here would reject a login before checking
 * it, and would tell the caller which passwords could not possibly exist.
 */
public record LoginRequest(

        @NotBlank(message = "email must not be blank")
        String email,

        @NotBlank(message = "password must not be blank")
        String password) {
}
