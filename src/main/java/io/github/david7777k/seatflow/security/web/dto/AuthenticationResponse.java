package io.github.david7777k.seatflow.security.web.dto;

import io.github.david7777k.seatflow.security.domain.Role;

public record AuthenticationResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        Long userId,
        String email,
        Role role) {
}
