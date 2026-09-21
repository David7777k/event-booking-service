package io.github.david7777k.seatflow;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Base class for tests that need a real database.
 *
 * <p>H2 is deliberately not used: this project depends on PostgreSQL-specific
 * behaviour — row locking semantics, exclusion constraints, generated tsvector
 * columns — none of which H2 reproduces. A test that passes against H2 would
 * prove nothing about the property this service is built around.
 *
 * <p>The container is static, so a single PostgreSQL instance is shared by every
 * test class in the run rather than started per class.
 */
@SpringBootTest
@Import(AbstractIntegrationTest.DatabaseConfiguration.class)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /**
     * Isolation between tests comes from truncating tables, not from rolling
     * back a surrounding transaction.
     *
     * <p>A test-managed transaction would make every write invisible to other
     * connections, which is precisely what the concurrency tests later in this
     * project need to observe. Using one strategy everywhere keeps those tests
     * from being a special case that behaves differently from the rest.
     */
    @AfterEach
    void truncateAllTables() {
        jdbcTemplate.execute("""
                truncate table event_seat, booking, event, seat, venue, app_user
                restart identity cascade
                """);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DatabaseConfiguration {

        @Bean
        DynamicPropertyRegistrar postgresProperties() {
            return registry -> {
                registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
                registry.add("spring.datasource.username", POSTGRES::getUsername);
                registry.add("spring.datasource.password", POSTGRES::getPassword);

                // A throwaway signing key. The application refuses to start
                // without one, which is the point of having no default.
                registry.add("seatflow.security.jwt.secret",
                        () -> "test-only-signing-key-at-least-32-characters-long");

                // BCrypt at production cost would add minutes to a suite that
                // creates users in nearly every test.
                registry.add("seatflow.security.bcrypt-strength", () -> 4);
            };
        }
    }

    /**
     * Acts as an ordinary account holder with the given id.
     *
     * <p>Authorities are stated explicitly as well as being put in the claim.
     * The {@code jwt()} post-processor builds the authentication directly and
     * does not run the application's {@code JwtAuthenticationConverter}, so a
     * {@code roles} claim on its own would leave the request with no
     * authorities at all - and every protected endpoint answering 403.
     */
    protected static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor asUser(long userId) {
        return jwt()
                .jwt(token -> token
                        .subject(String.valueOf(userId))
                        .claim("roles", List.of("USER")))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    /** Acts as an administrator, who may also manage the catalogue. */
    protected static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor asAdmin() {
        return asAdmin(1L);
    }

    protected static SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor asAdmin(long userId) {
        return jwt()
                .jwt(token -> token
                        .subject(String.valueOf(userId))
                        .claim("roles", List.of("ADMIN")))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    /**
     * Inserts a user straight into the table.
     *
     * <p>The password hash is a placeholder: tests that drive the API as this
     * user authenticate with {@link #asUser}, and only the security tests go
     * through a real login.
     */
    protected long createUser(String email) {
        return jdbcTemplate.queryForObject("""
                insert into app_user (email, password_hash, role)
                values (?, 'not-a-real-hash', 'USER')
                returning id
                """, Long.class, email);
    }
}
