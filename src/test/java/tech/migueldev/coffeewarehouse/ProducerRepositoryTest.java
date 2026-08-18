package tech.migueldev.coffeewarehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tech.migueldev.coffeewarehouse.domain.Producer;
import tech.migueldev.coffeewarehouse.repository.ProducerRepository;

import jakarta.persistence.EntityManager;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The constraints that live in the database, exercised against the database.
 *
 * These are the assertions an H2-backed test could not make honestly: the
 * unique index and the CHECK are Postgres artifacts, and the whole point of the
 * Testcontainers decision is that they get to fail here rather than in
 * production.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("integration")
class ProducerRepositoryTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = TestPostgres.INSTANCE;

    @Autowired
    private ProducerRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("unique index rejects a second producer with the same code")
    void rejectsDuplicateCode() {
        repository.saveAndFlush(new Producer("DUP-001", "Primeira", "Guaxupe", "MG"));

        assertThatThrownBy(() ->
                repository.saveAndFlush(new Producer("DUP-001", "Segunda", "Franca", "SP")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("check constraint rejects a state that is not two uppercase letters")
    void rejectsInvalidState() {
        // Inserted with native SQL on purpose: the entity normalizes state to
        // uppercase, so the only way to reach the CHECK is to go around it.
        // A native query skips Spring's exception translation, so what surfaces
        // is Hibernate's own type rather than DataIntegrityViolationException.
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("""
                            INSERT INTO producer (code, name, city, state, created_at, updated_at, version)
                            VALUES ('BAD-001', 'Invalida', 'Guaxupe', 'm1', now(), now(), 0)
                            """)
                    .executeUpdate();
            entityManager.flush();
        }).isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("ck_producer_state");
    }

    @Test
    @DisplayName("audit columns are filled on insert and the version moves on update")
    void maintainsAuditColumns() {
        Producer saved = repository.saveAndFlush(new Producer("AUD-001", "Nome Antigo", "Guaxupe", "MG"));

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getVersion()).isZero();

        saved.updateDetails("Nome Novo", "Franca", "SP");
        Producer updated = repository.saveAndFlush(saved);

        assertThat(updated.getVersion()).isEqualTo(1L);
        assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(updated.getCreatedAt());
        assertThat(updated.getName()).isEqualTo("Nome Novo");
    }

    @Test
    @DisplayName("finds a producer by its business code")
    void findsByCode() {
        repository.saveAndFlush(new Producer("FIND-001", "Encontrada", "Guaxupe", "MG"));

        assertThat(repository.findByCode("FIND-001")).isPresent();
        assertThat(repository.existsByCode("FIND-001")).isTrue();
        assertThat(repository.existsByCode("MISSING")).isFalse();
    }
}
