package tech.migueldev.coffeewarehouse;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests.
 *
 * Starts a real Postgres through Testcontainers and lets Flyway run the
 * migrations against it. No H2: the test database is the same engine as the
 * runtime one, so constraints, NUMERIC types and checks are exercised for real.
 *
 * Singleton container pattern: the container is started once from a static
 * initializer and never stopped, so it outlives every test class in the JVM.
 * The @Testcontainers/@Container extension would stop it when the first test
 * class finishes, and the next class -- reusing Spring's cached context, and
 * with it the cached JDBC URL -- would connect to a port that no longer exists.
 * Ryuk removes the container when the JVM exits.
 */
@SpringBootTest
@Tag("integration")
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("coffee_warehouse")
                    .withUsername("coffee")
                    .withPassword("coffee");

    static {
        POSTGRES.start();
    }
}
