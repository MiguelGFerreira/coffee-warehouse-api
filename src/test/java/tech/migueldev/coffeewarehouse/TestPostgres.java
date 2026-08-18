package tech.migueldev.coffeewarehouse;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The one Postgres container the whole test suite talks to.
 *
 * Started from a static initializer and never stopped, so it outlives every
 * test class in the JVM. The @Testcontainers/@Container extension would stop it
 * when the first test class finishes, and the next class -- reusing Spring's
 * cached context, and with it the cached JDBC URL -- would connect to a port
 * that no longer exists. Ryuk removes the container when the JVM exits.
 *
 * Test classes expose it as a @ServiceConnection field so Spring Boot points
 * the DataSource at it. Sharing one instance keeps the suite to a single
 * container even though the slice and the full-context tests build separate
 * application contexts.
 */
final class TestPostgres {

    static final PostgreSQLContainer<?> INSTANCE =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("coffee_warehouse")
                    .withUsername("coffee")
                    .withPassword("coffee");

    static {
        INSTANCE.start();
    }

    private TestPostgres() {
    }
}
