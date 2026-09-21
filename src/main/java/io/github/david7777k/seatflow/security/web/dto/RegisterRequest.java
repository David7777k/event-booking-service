package io.github.david7777k.seatflow.security.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(

        @NotBlank(message = "email must not be blank")
        @Email(message = "email must be a valid address")
        @Size(max = 254, message = "email must be at most 254 characters")
        String email,

        /**
         * Length is the only rule. Composition requirements (an uppercase, a
         * digit, a symbol) push people towards predictable substitutions and
         * buy less than the extra characters they discourage.
         */
        @NotBlank(message = "password must not be blank")
        @Size(min = 12, max = 200, message = "password must be between 12 and 200 characters")
        String password) {
}
