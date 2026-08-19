package tech.migueldev.coffeewarehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tech.migueldev.coffeewarehouse.domain.StoragePosition;
import tech.migueldev.coffeewarehouse.domain.Warehouse;
import tech.migueldev.coffeewarehouse.repository.StoragePositionRepository;
import tech.migueldev.coffeewarehouse.repository.WarehouseRepository;

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

/**
 * The storage position constraints that live in the database.
 *
 * The rows that have to violate a CHECK go in through native SQL: the entity
 * normalizes and derives, so the only way to reach the constraint is around the
 * domain.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("integration")
class StoragePositionRepositoryTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = TestPostgres.INSTANCE;

    @Autowired
    private StoragePositionRepository repository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private EntityManager entityManager;

    private Warehouse warehouse;

    @BeforeEach
    void seedWarehouse() {
        warehouse = warehouseRepository.saveAndFlush(
                new Warehouse("WHC", "Armazem Central", "Guaxupe", "MG"));
    }

    @Test
    @DisplayName("unique index rejects a second position with the same derived code")
    void rejectsDuplicateCode() {
        repository.saveAndFlush(
                new StoragePosition(warehouse, "01", "01", "01", new BigDecimal("1000.000")));

        assertThatThrownBy(() -> repository.saveAndFlush(
                new StoragePosition(warehouse, "01", "01", "01", new BigDecimal("2000.000"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("unique index rejects two positions sharing an address under a different code")
    void rejectsDuplicateAddress() {
        repository.saveAndFlush(
                new StoragePosition(warehouse, "02", "03", "04", new BigDecimal("1000.000")));

        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("""
                            INSERT INTO storage_position
                                (warehouse_id, aisle, bay, level, code, capacity_kg, active,
                                 created_at, updated_at, version)
                            VALUES (:warehouseId, '02', '03', '04', 'ANOTHER-CODE', 1000.000, true,
                                    now(), now(), 0)
                            """)
                    .setParameter("warehouseId", warehouse.getId())
                    .executeUpdate();
            entityManager.flush();
        }).isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("uk_storage_position_address");
    }

    @Test
    @DisplayName("check constraint rejects a capacity that is not greater than zero")
    void rejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("""
                            INSERT INTO storage_position
                                (warehouse_id, aisle, bay, level, code, capacity_kg, active,
                                 created_at, updated_at, version)
                            VALUES (:warehouseId, '09', '09', '09', 'WHC-A09-B09-L09', 0, true,
                                    now(), now(), 0)
                            """)
                    .setParameter("warehouseId", warehouse.getId())
                    .executeUpdate();
            entityManager.flush();
        }).isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("ck_storage_position_capacity");
    }

    @Test
    @DisplayName("foreign key rejects a position pointing at a warehouse that does not exist")
    void rejectsUnknownWarehouse() {
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("""
                            INSERT INTO storage_position
                                (warehouse_id, aisle, bay, level, code, capacity_kg, active,
                                 created_at, updated_at, version)
                            VALUES (999999, '08', '08', '08', 'ORPHAN-A08-B08-L08', 1000.000, true,
                                    now(), now(), 0)
                            """)
                    .executeUpdate();
            entityManager.flush();
        }).isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("fk_storage_position_warehouse");
    }

    @Test
    @DisplayName("reads a position with its warehouse already loaded")
    void loadsWarehouseWithPosition() {
        StoragePosition saved = repository.saveAndFlush(
                new StoragePosition(warehouse, "05", "06", "07", new BigDecimal("1000.000")));
        entityManager.clear();

        assertThat(repository.findByCode("WHC-A05-B06-L07"))
                .isPresent()
                .get()
                .satisfies(position -> assertThat(position.getWarehouse().getCode()).isEqualTo("WHC"));
        assertThat(saved.getCode()).isEqualTo("WHC-A05-B06-L07");
    }
}
