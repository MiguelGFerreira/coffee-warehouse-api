package tech.migueldev.coffeewarehouse;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests.
 *
 * Starts a real Postgres through Testcontainers and lets Flyway run the
 * migrations against it. No H2: the test database is the same engine as the
 * runtime one, so constraints, NUMERIC types and checks are exercised for real.
 *
 * The container is static -> reused across every subclass.
 */
@Testcontainers
@SpringBootTest
@Tag("integration")
public abstract class AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("coffee_warehouse")
                    .withUsername("coffee")
                    .withPassword("coffee");
}
