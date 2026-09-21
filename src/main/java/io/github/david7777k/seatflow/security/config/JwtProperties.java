package io.github.david7777k.seatflow.security.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Signing configuration.
 *
 * <p>The secret is validated for length rather than merely for presence.
 * HS256 keys shorter than 256 bits weaken the signature, and a service that
 * starts happily with a four-character secret is worse than one that refuses
 * to start at all.
 */
@Validated
@ConfigurationProperties(prefix = "seatflow.security.jwt")
public record JwtProperties(

        @NotBlank(message = "a JWT signing secret must be configured")
        @Size(min = 32, message = "the JWT secret must be at least 32 characters for HS256")
        String secret,

        Duration accessTokenTtl,

        String issuer) {

    public JwtProperties {
        accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(30) : accessTokenTtl;
        issuer = issuer == null ? "seatflow" : issuer;
    }
}
