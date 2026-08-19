package tech.migueldev.coffeewarehouse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for full-context integration tests.
 *
 * Runs against the real Postgres of {@link TestPostgres} and lets Flyway apply
 * the migrations to it. No H2: the test database is the same engine as the
 * runtime one, so constraints, NUMERIC types and checks are exercised for real.
 */
@SpringBootTest
@Tag("integration")
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = TestPostgres.INSTANCE;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Every test starts from an empty database.
     *
     * One container is shared by the whole suite, so rows left behind by one
     * class are visible to the next -- and a per-class deleteAll() on one table
     * fails on a foreign key as soon as another class has written to a table
     * that references it. Truncating the four together with CASCADE sidesteps
     * the ordering problem and is cheaper than deleting row by row.
     */
    @BeforeEach
    void resetDatabase() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE lot, storage_position, warehouse, producer RESTART IDENTITY CASCADE");
    }
}
