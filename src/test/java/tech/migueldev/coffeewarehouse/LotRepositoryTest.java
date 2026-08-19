package tech.migueldev.coffeewarehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tech.migueldev.coffeewarehouse.domain.Lot;
import tech.migueldev.coffeewarehouse.domain.LotStatus;
import tech.migueldev.coffeewarehouse.domain.Producer;
import tech.migueldev.coffeewarehouse.repository.LotRepository;
import tech.migueldev.coffeewarehouse.repository.ProducerRepository;

import jakarta.persistence.EntityManager;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The lot constraints that live in the database.
 *
 * The CHECK on status matters more than it looks: it is the guarantee that a
 * value outside the lifecycle cannot reach the column even if some future code
 * path writes it without going through the enum.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("integration")
class LotRepositoryTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = TestPostgres.INSTANCE;

    @Autowired
    private LotRepository repository;

    @Autowired
    private ProducerRepository producerRepository;

    @Autowired
    private EntityManager entityManager;

    private Producer producer;

    @BeforeEach
    void seedProducer() {
        producer = producerRepository.saveAndFlush(
                new Producer("COP-900", "Cooperativa Serra Alta", "Guaxupe", "MG"));
    }

    private Lot newLot(String code) {
        return new Lot(code, producer, 2025, new BigDecimal("18000.000"), LocalDate.of(2025, 6, 10));
    }

    private void insertRaw(String code, String column, String value) {
        entityManager.createNativeQuery("""
                        INSERT INTO lot (code, producer_id, crop_year, net_weight_kg, received_on,
                                         status, %s, created_at, updated_at, version)
                        VALUES (:code, :producerId, 2025, 18000.000, DATE '2025-06-10',
                                'AWAITING_ALLOCATION', %s, now(), now(), 0)
                        """.formatted(column, value))
                .setParameter("code", code)
                .setParameter("producerId", producer.getId())
                .executeUpdate();
        entityManager.flush();
    }

    @Test
    @DisplayName("unique index rejects a second lot with the same code")
    void rejectsDuplicateCode() {
        repository.saveAndFlush(newLot("LOT-900"));

        assertThatThrownBy(() -> repository.saveAndFlush(newLot("LOT-900")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a new lot lands in the database awaiting allocation")
    void startsAwaitingAllocation() {
        Lot saved = repository.saveAndFlush(newLot("LOT-901"));
        entityManager.clear();

        assertThat(repository.findByCode("LOT-901"))
                .isPresent()
                .get()
                .satisfies(lot -> {
                    assertThat(lot.getStatus()).isEqualTo(LotStatus.AWAITING_ALLOCATION);
                    assertThat(lot.getProducer().getCode()).isEqualTo("COP-900");
                });
        assertThat(saved.getStatus()).isEqualTo(LotStatus.AWAITING_ALLOCATION);
    }

    @Test
    @DisplayName("check constraint rejects a net weight that is not greater than zero")
    void rejectsNonPositiveWeight() {
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("""
                            INSERT INTO lot (code, producer_id, crop_year, net_weight_kg, received_on,
                                             status, created_at, updated_at, version)
                            VALUES ('LOT-902', :producerId, 2025, 0, DATE '2025-06-10',
                                    'AWAITING_ALLOCATION', now(), now(), 0)
                            """)
                    .setParameter("producerId", producer.getId())
                    .executeUpdate();
            entityManager.flush();
        }).isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("ck_lot_net_weight");
    }

    @Test
    @DisplayName("check constraint rejects a crop year outside the allowed range")
    void rejectsCropYearOutOfRange() {
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("""
                            INSERT INTO lot (code, producer_id, crop_year, net_weight_kg, received_on,
                                             status, created_at, updated_at, version)
                            VALUES ('LOT-903', :producerId, 1800, 18000.000, DATE '2025-06-10',
                                    'AWAITING_ALLOCATION', now(), now(), 0)
                            """)
                    .setParameter("producerId", producer.getId())
                    .executeUpdate();
            entityManager.flush();
        }).isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("ck_lot_crop_year");
    }

    @Test
    @DisplayName("check constraint rejects a moisture percentage above one hundred")
    void rejectsMoistureAboveOneHundred() {
        assertThatThrownBy(() -> insertRaw("LOT-904", "moisture_percent", "120.00"))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("ck_lot_moisture");
    }

    @Test
    @DisplayName("check constraint rejects a status outside the lot lifecycle")
    void rejectsStatusOutsideLifecycle() {
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("""
                            INSERT INTO lot (code, producer_id, crop_year, net_weight_kg, received_on,
                                             status, created_at, updated_at, version)
                            VALUES ('LOT-905', :producerId, 2025, 18000.000, DATE '2025-06-10',
                                    'INVENTED_STATUS', now(), now(), 0)
                            """)
                    .setParameter("producerId", producer.getId())
                    .executeUpdate();
            entityManager.flush();
        }).isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("ck_lot_status");
    }

    @Test
    @DisplayName("check constraint rejects a bag count that is not positive")
    void rejectsNonPositiveBags() {
        assertThatThrownBy(() -> insertRaw("LOT-906", "bags", "0"))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("ck_lot_bags");
    }
}
