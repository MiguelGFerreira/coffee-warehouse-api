package tech.migueldev.coffeewarehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tech.migueldev.coffeewarehouse.domain.Lot;
import tech.migueldev.coffeewarehouse.domain.Producer;
import tech.migueldev.coffeewarehouse.domain.Shipment;
import tech.migueldev.coffeewarehouse.domain.StoragePosition;
import tech.migueldev.coffeewarehouse.domain.Warehouse;
import tech.migueldev.coffeewarehouse.repository.LotRepository;
import tech.migueldev.coffeewarehouse.repository.ProducerRepository;
import tech.migueldev.coffeewarehouse.repository.ShipmentRepository;
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
import java.time.LocalDate;

/**
 * The shipment constraints that live in the database.
 *
 * The interesting ones here are the two that keep a row's story consistent with
 * itself: a shipment cannot claim to be confirmed without saying when, and it
 * cannot carry a frozen blend unless it is confirmed. Those are invariants the
 * service already respects -- which is exactly why they are worth pinning in the
 * schema, where a seed, a migration or a fix applied by hand still has to obey
 * them.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("integration")
class ShipmentRepositoryTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = TestPostgres.INSTANCE;

    @Autowired
    private ShipmentRepository repository;

    @Autowired
    private ProducerRepository producerRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private StoragePositionRepository positionRepository;

    @Autowired
    private LotRepository lotRepository;

    @Autowired
    private EntityManager entityManager;

    private Lot lot;
    private StoragePosition position;

    @BeforeEach
    void seed() {
        Producer producer = producerRepository.saveAndFlush(
                new Producer("COP-800", "Cooperativa Serra Alta", "Guaxupe", "MG"));
        Warehouse warehouse = warehouseRepository.saveAndFlush(
                new Warehouse("WHS", "Armazem do Embarque", "Guaxupe", "MG"));
        position = positionRepository.saveAndFlush(
                new StoragePosition(warehouse, "01", "01", "01", new BigDecimal("60000.000")));
        lot = lotRepository.saveAndFlush(new Lot("LOT-800", producer, 2025,
                new BigDecimal("18000.000"), LocalDate.of(2025, 6, 10)));
    }

    private Shipment draft(String code) {
        return repository.saveAndFlush(
                new Shipment(code, "Port of Santos", LocalDate.of(2030, 7, 1)));
    }

    @Test
    @DisplayName("unique index rejects a second shipment with the same code")
    void rejectsDuplicateCode() {
        draft("SHP-800");

        assertThatThrownBy(() -> draft("SHP-800"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("check constraint rejects a confirmed shipment that does not say when")
    void rejectsConfirmedWithoutTimestamp() {
        assertThatThrownBy(() -> insertShipment("SHP-801", "CONFIRMED", null, "1000.000"))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("ck_shipment_confirmed_at");
    }

    @Test
    @DisplayName("check constraint rejects a draft shipment that claims a confirmation time")
    void rejectsDraftWithTimestamp() {
        assertThatThrownBy(() -> insertShipment("SHP-802", "DRAFT", "now()", null))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("ck_shipment_confirmed_at");
    }

    @Test
    @DisplayName("check constraint rejects a confirmed shipment with no frozen weight")
    void rejectsConfirmedWithoutBlendSnapshot() {
        assertThatThrownBy(() -> insertShipment("SHP-803", "CONFIRMED", "now()", null))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("ck_shipment_blend_snapshot");
    }

    /**
     * The snapshot belongs to history, so a shipment that has not shipped
     * anything must not be carrying one.
     */
    @Test
    @DisplayName("check constraint rejects a draft shipment carrying a frozen blend")
    void rejectsDraftWithBlendSnapshot() {
        assertThatThrownBy(() -> insertShipment("SHP-804", "DRAFT", null, "1000.000"))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("ck_shipment_blend_snapshot");
    }

    @Test
    @DisplayName("check constraint rejects a status outside the enum")
    void rejectsUnknownStatus() {
        assertThatThrownBy(() -> insertShipment("SHP-805", "DISPATCHED", null, null))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("ck_shipment_status");
    }

    @Test
    @DisplayName("unique index rejects two lines for the same lot out of the same position")
    void rejectsDuplicateLine() {
        Shipment shipment = draft("SHP-806");
        insertItem(shipment, "1000.000");

        assertThatThrownBy(() -> insertItem(shipment, "500.000"))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("uk_shipment_item_line");
    }

    @Test
    @DisplayName("check constraint rejects a line weight that is not greater than zero")
    void rejectsNonPositiveItemWeight() {
        Shipment shipment = draft("SHP-807");

        assertThatThrownBy(() -> insertItem(shipment, "0"))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("ck_shipment_item_weight");
    }

    @Test
    @DisplayName("reads a shipment with its items and their lots already loaded")
    void loadsItemsWithShipment() {
        Shipment shipment = draft("SHP-808");
        shipment.addItem(lot, position, new BigDecimal("1000.000"));
        repository.saveAndFlush(shipment);
        entityManager.clear();

        assertThat(repository.findById(shipment.getId()))
                .isPresent()
                .get()
                .satisfies(found -> {
                    assertThat(found.getItems()).hasSize(1);
                    assertThat(found.getItems().get(0).getLot().getCode()).isEqualTo("LOT-800");
                    assertThat(found.getItems().get(0).getSourcePosition().getCode())
                            .isEqualTo("WHS-A01-B01-L01");
                    // The blend needs the lots, so a shipment read without them
                    // would fail outside the session rather than here.
                    assertThat(found.blend().totalWeightKg()).isEqualByComparingTo("1000.000");
                });
    }

    /**
     * The blend columns are CAST for the same reason the ledger test casts its
     * position ids: half of these cases pass a null, and Postgres cannot infer
     * the type of a bare null parameter.
     */
    private void insertShipment(String code, String status, String confirmedAt,
                                String blendWeightKg) {
        entityManager.createNativeQuery("""
                        INSERT INTO shipment
                            (code, status, destination, confirmed_at, blend_weight_kg,
                             created_at, updated_at, version)
                        VALUES (:code, :status, 'Port of Santos', %s,
                                CAST(:blendWeight AS NUMERIC), now(), now(), 0)
                        """.formatted(confirmedAt == null ? "NULL" : confirmedAt))
                .setParameter("code", code)
                .setParameter("status", status)
                .setParameter("blendWeight", blendWeightKg)
                .executeUpdate();
        entityManager.flush();
    }

    private void insertItem(Shipment shipment, String weight) {
        entityManager.createNativeQuery("""
                        INSERT INTO shipment_item
                            (shipment_id, lot_id, source_position_id, weight_kg,
                             created_at, updated_at, version)
                        VALUES (:shipmentId, :lotId, :positionId, :weight, now(), now(), 0)
                        """)
                .setParameter("shipmentId", shipment.getId())
                .setParameter("lotId", lot.getId())
                .setParameter("positionId", position.getId())
                .setParameter("weight", new BigDecimal(weight))
                .executeUpdate();
        entityManager.flush();
    }
}
