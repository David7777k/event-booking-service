package io.github.david7777k.seatflow.security.web;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Reads the caller's identity from the verified token.
 *
 * <p>The point of this class is that there is exactly one way to learn who is
 * calling, and it goes through a token the filter chain has already validated.
 * Before this existed the caller announced its own user id in the request body,
 * which meant anyone could act as anyone.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static long id(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }

    public static boolean isAdmin(Jwt jwt) {
        Object roles = jwt.getClaim("roles");
        return roles instanceof Iterable<?> values
                && java.util.stream.StreamSupport.stream(values.spliterator(), false)
                        .anyMatch("ADMIN"::equals);
    }
}
