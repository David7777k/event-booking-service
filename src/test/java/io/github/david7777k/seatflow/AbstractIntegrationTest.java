package io.github.david7777k.seatflow;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.PostgreSQLContainer;

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
            };
        }
    }
}
