package tech.migueldev.coffeewarehouse;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base para testes de integracao.
 *
 * Sobe um Postgres real via Testcontainers e deixa o Flyway rodar as migrations
 * contra ele. Nada de H2: o banco de teste e o mesmo do runtime, entao as
 * constraints, tipos NUMERIC e checks sao exercitados de verdade.
 *
 * O container e estatico -> reaproveitado por todas as classes filhas.
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
