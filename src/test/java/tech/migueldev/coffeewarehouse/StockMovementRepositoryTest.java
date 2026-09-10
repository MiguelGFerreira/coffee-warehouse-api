package tech.migueldev.coffeewarehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tech.migueldev.coffeewarehouse.domain.Lot;
import tech.migueldev.coffeewarehouse.domain.Producer;
import tech.migueldev.coffeewarehouse.domain.StockMovement;
import tech.migueldev.coffeewarehouse.domain.StoragePosition;
import tech.migueldev.coffeewarehouse.domain.Warehouse;
import tech.migueldev.coffeewarehouse.repository.LotRepository;
import tech.migueldev.coffeewarehouse.repository.ProducerRepository;
import tech.migueldev.coffeewarehouse.repository.StockMovementRepository;
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
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The ledger constraints that live in the database, and the aggregations read
 * back off them.
 *
 * The factory methods on {@link StockMovement} make an impossible movement
 * inexpressible in Java, which is the point of them -- so every row that has to
 * violate a CHECK goes in through native SQL, around the domain. That is not
 * belt and braces: the schema is the last line of defence for anything written
 * by a migration, a seed, a fix applied by hand, or a second service later on.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("integration")
class StockMovementRepositoryTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = TestPostgres.INSTANCE;

    @Autowired
    private StockMovementRepository repository;

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
    private StoragePosition positionA;
    private StoragePosition positionB;

    @BeforeEach
    void seed() {
        Producer producer = producerRepository.saveAndFlush(
                new Producer("COP-900", "Cooperativa Serra Alta", "Guaxupe", "MG"));
        Warehouse warehouse = warehouseRepository.saveAndFlush(
                new Warehouse("WHL", "Armazem do Ledger", "Guaxupe", "MG"));
        positionA = positionRepository.saveAndFlush(
                new StoragePosition(warehouse, "01", "01", "01", new BigDecimal("60000.000")));
        positionB = positionRepository.saveAndFlush(
                new StoragePosition(warehouse, "02", "01", "01", new BigDecimal("60000.000")));
        lot = lotRepository.saveAndFlush(new Lot("LOT-900", producer, 2025,
                new BigDecimal("18000.000"), LocalDate.of(2025, 6, 10)));
    }

    @Test
    @DisplayName("check constraint rejects an inbound that carries a source position")
    void rejectsInboundWithSource() {
        assertThatThrownBy(() -> insertMovement("INBOUND", positionA.getId(), positionB.getId(), "100.000"))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("ck_stock_movement_endpoints");
    }

    @Test
    @DisplayName("check constraint rejects an inbound with no target position")
    void rejectsInboundWithoutTarget() {
        assertThatThrownBy(() -> insertMovement("INBOUND", null, null, "100.000"))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("ck_stock_movement_endpoints");
    }

    @Test
    @DisplayName("check constraint rejects an outbound that carries a target position")
    void rejectsOutboundWithTarget() {
        assertThatThrownBy(() -> insertMovement("OUTBOUND", positionA.getId(), positionB.getId(), "100.000"))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("ck_stock_movement_endpoints");
    }

    @Test
    @DisplayName("check constraint rejects a transfer from a position to itself")
    void rejectsTransferToTheSamePosition() {
        assertThatThrownBy(() -> insertMovement("TRANSFER", positionA.getId(), positionA.getId(), "100.000"))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("ck_stock_movement_endpoints");
    }

    @Test
    @DisplayName("check constraint rejects a transfer missing one of its ends")
    void rejectsTransferWithOneEnd() {
        assertThatThrownBy(() -> insertMovement("TRANSFER", positionA.getId(), null, "100.000"))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("ck_stock_movement_endpoints");
    }

    @Test
    @DisplayName("check constraint rejects a weight that is not greater than zero")
    void rejectsNonPositiveWeight() {
        assertThatThrownBy(() -> insertMovement("INBOUND", null, positionA.getId(), "0"))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("ck_stock_movement_weight");
    }

    /**
     * A type outside the enum is refused -- but by the endpoints CHECK, not by
     * the type CHECK. Every branch of the endpoints constraint names one of the
     * three types, so an unknown type matches no branch and fails there first.
     * `ck_stock_movement_type` is therefore redundant given the endpoints one;
     * it is kept because it states the allowed set in a place a reader looks,
     * and it would still hold if the endpoints rule were ever relaxed. It
     * cannot be isolated by a test, which is why this asserts what actually
     * fires rather than what the constraint list suggests should.
     */
    @Test
    @DisplayName("check constraint rejects a movement type outside the enum")
    void rejectsUnknownType() {
        assertThatThrownBy(() -> insertMovement("ADJUSTMENT", null, positionA.getId(), "100.000"))
                .isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("ck_stock_movement_endpoints");
    }

    @Test
    @DisplayName("foreign key rejects a movement pointing at a lot that does not exist")
    void rejectsUnknownLot() {
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("""
                            INSERT INTO stock_movement
                                (type, lot_id, source_position_id, target_position_id,
                                 weight_kg, occurred_at, created_at)
                            VALUES ('INBOUND', 999999, NULL, :target, 100.000, now(), now())
                            """)
                    .setParameter("target", positionA.getId())
                    .executeUpdate();
            entityManager.flush();
        }).isInstanceOf(ConstraintViolationException.class)
                .hasMessageContaining("fk_stock_movement_lot");
    }

    /**
     * NUMERIC(12,3) has to hold the third decimal exactly. A double would have
     * lost it, which is the reason weight is BigDecimal all the way down.
     */
    @Test
    @DisplayName("stores and sums weights at the full three decimal places")
    void keepsThreeDecimalPlaces() {
        repository.saveAndFlush(StockMovement.inbound(
                lot, positionA, new BigDecimal("0.001"), null, null));
        repository.saveAndFlush(StockMovement.inbound(
                lot, positionA, new BigDecimal("0.002"), null, null));
        entityManager.clear();

        assertThat(repository.occupancyOf(positionA.getId())).isEqualByComparingTo("0.003");
    }

    /**
     * The COALESCE in every aggregation exists for this case. Without it the
     * first inbound into a position would add a weight to null.
     */
    @Test
    @DisplayName("aggregations return zero, never null, for a position and a lot with no history")
    void aggregationsReturnZeroWhenThereIsNoHistory() {
        assertThat(repository.occupancyOf(positionA.getId())).isEqualByComparingTo("0");
        assertThat(repository.balanceOfLot(lot.getId())).isEqualByComparingTo("0");
        assertThat(repository.balanceOfLotAt(lot.getId(), positionA.getId()))
                .isEqualByComparingTo("0");
    }

    /**
     * A transfer is the case the aggregations have to get right: it leaves the
     * lot balance alone while moving the occupancy from one position to another.
     */
    @Test
    @DisplayName("a transfer nets to zero for the lot and moves the weight between positions")
    void transferMovesOccupancyWithoutChangingTheLotBalance() {
        repository.saveAndFlush(StockMovement.inbound(
                lot, positionA, new BigDecimal("1000.000"), null, null));
        repository.saveAndFlush(StockMovement.transfer(
                lot, positionA, positionB, new BigDecimal("400.000"), null, null));
        entityManager.clear();

        assertThat(repository.occupancyOf(positionA.getId())).isEqualByComparingTo("600.000");
        assertThat(repository.occupancyOf(positionB.getId())).isEqualByComparingTo("400.000");
        assertThat(repository.balanceOfLot(lot.getId())).isEqualByComparingTo("1000.000");
        assertThat(repository.balanceOfLotAt(lot.getId(), positionB.getId()))
                .isEqualByComparingTo("400.000");
    }

    /**
     * Occupancy is per position across every lot; balanceOfLotAt narrows it to
     * one. A position holding two lots is what tells the two queries apart.
     */
    @Test
    @DisplayName("occupancy counts every lot in the position, the lot balance only its own")
    void occupancySpansLotsWhileTheLotBalanceDoesNot() {
        Lot other = lotRepository.saveAndFlush(new Lot("LOT-901", lot.getProducer(), 2025,
                new BigDecimal("5000.000"), LocalDate.of(2025, 6, 11)));
        repository.saveAndFlush(StockMovement.inbound(
                lot, positionA, new BigDecimal("1000.000"), null, null));
        repository.saveAndFlush(StockMovement.inbound(
                other, positionA, new BigDecimal("250.000"), null, null));
        entityManager.clear();

        assertThat(repository.occupancyOf(positionA.getId())).isEqualByComparingTo("1250.000");
        assertThat(repository.balanceOfLotAt(lot.getId(), positionA.getId()))
                .isEqualByComparingTo("1000.000");
        assertThat(repository.balanceOfLotAt(other.getId(), positionA.getId()))
                .isEqualByComparingTo("250.000");
    }

    /**
     * The rule the whole project rests on, enforced where it cannot be argued
     * with.
     *
     * Until V4 "never UPDATE, never DELETE" bound the application and nothing
     * else: mapped columns, absent setters, a repository that does not expose
     * delete. A psql session or a fix typed by hand could still rewrite history.
     * These two go around every Java guard and straight at the table.
     */
    @Test
    @DisplayName("the database refuses to update a movement that is already recorded")
    void refusesToUpdateALedgerRow() {
        StockMovement movement = repository.saveAndFlush(StockMovement.inbound(
                lot, positionA, new BigDecimal("1000.000"), null, null));

        assertThatThrownBy(() -> {
            entityManager.createNativeQuery(
                            "UPDATE stock_movement SET weight_kg = 9999 WHERE id = :id")
                    .setParameter("id", movement.getId())
                    .executeUpdate();
            entityManager.flush();
        }).hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("the database refuses to delete a movement that is already recorded")
    void refusesToDeleteALedgerRow() {
        StockMovement movement = repository.saveAndFlush(StockMovement.inbound(
                lot, positionA, new BigDecimal("1000.000"), null, null));

        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("DELETE FROM stock_movement WHERE id = :id")
                    .setParameter("id", movement.getId())
                    .executeUpdate();
            entityManager.flush();
        }).hasMessageContaining("append-only");
    }

    /**
     * The position ids are CAST explicitly because half of these cases pass one
     * of them as null, and Postgres cannot infer the type of a bare null
     * parameter -- it would fail on the wrong thing and prove nothing.
     */
    private void insertMovement(String type, Long sourceId, Long targetId, String weight) {
        entityManager.createNativeQuery("""
                        INSERT INTO stock_movement
                            (type, lot_id, source_position_id, target_position_id,
                             weight_kg, occurred_at, created_at)
                        VALUES (:type, :lotId, CAST(:source AS BIGINT), CAST(:target AS BIGINT),
                                :weight, now(), now())
                        """)
                .setParameter("type", type)
                .setParameter("lotId", lot.getId())
                .setParameter("source", sourceId)
                .setParameter("target", targetId)
                .setParameter("weight", new BigDecimal(weight))
                .executeUpdate();
        entityManager.flush();
    }
}
