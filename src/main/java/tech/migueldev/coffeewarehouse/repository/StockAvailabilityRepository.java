package tech.migueldev.coffeewarehouse.repository;

import tech.migueldev.coffeewarehouse.domain.StockMovement;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What is actually free to pick, per lot and position, oldest crop first.
 *
 * <h2>Why this one is native SQL</h2>
 *
 * Every other query in the project is JPQL. This one cannot be: the ledger has
 * to be unpivoted before it can be grouped, because a single TRANSFER row
 * belongs to two different positions -- it adds to one and subtracts from the
 * other. Expressing that needs a UNION ALL, which JPQL has no syntax for. The
 * alternatives were a query per (lot, position) pair, which is an N+1 over the
 * whole warehouse, or reading the entire ledger into memory to group it there.
 * Native SQL for one read is the cheaper trade, and the reason is written down
 * so nobody has to rediscover it.
 *
 * <h2>What it returns</h2>
 *
 * Balance from the ledger, minus whatever draft shipments have already claimed
 * of the same lot at the same position -- the same definition of "available"
 * the composition check uses, computed for every pair at once instead of one at
 * a time. Pairs that net to nothing are dropped: a position a lot has entirely
 * left is not a picking option.
 */
public interface StockAvailabilityRepository extends Repository<StockMovement, Long> {

    /**
     * One pickable (lot, position) pair.
     *
     * An interface projection rather than a record because Spring Data maps
     * native query columns onto it by name without a constructor to keep in
     * step with the SELECT list.
     */
    interface AvailableStock {
        Long getLotId();

        String getLotCode();

        Integer getCropYear();

        LocalDate getReceivedOn();

        BigDecimal getMoisturePercent();

        Long getPositionId();

        String getPositionCode();

        BigDecimal getAvailableKg();
    }

    /**
     * FIFO is by crop year first, then by the date the lot was received, then by
     * code so that two lots received on the same day still come out in a stable
     * order. Without that last tiebreak the same request could return different
     * suggestions on consecutive calls, which makes the endpoint impossible to
     * test and unpleasant to trust.
     *
     * The optional filters are CAST because Postgres cannot infer the type of a
     * bare null bind parameter.
     */
    @Query(value = """
            SELECT l.id                AS "lotId",
                   l.code              AS "lotCode",
                   l.crop_year         AS "cropYear",
                   l.received_on       AS "receivedOn",
                   l.moisture_percent  AS "moisturePercent",
                   p.id                AS "positionId",
                   p.code              AS "positionCode",
                   b.balance - COALESCE(r.reserved, 0) AS "availableKg"
            FROM (
                SELECT lot_id, position_id, SUM(delta) AS balance
                FROM (
                    SELECT lot_id, target_position_id AS position_id, weight_kg AS delta
                    FROM stock_movement
                    WHERE target_position_id IS NOT NULL
                    UNION ALL
                    SELECT lot_id, source_position_id AS position_id, -weight_kg AS delta
                    FROM stock_movement
                    WHERE source_position_id IS NOT NULL
                ) unpivoted
                GROUP BY lot_id, position_id
            ) b
            JOIN lot l              ON l.id = b.lot_id
            JOIN storage_position p ON p.id = b.position_id
            LEFT JOIN (
                SELECT si.lot_id, si.source_position_id, SUM(si.weight_kg) AS reserved
                FROM shipment_item si
                JOIN shipment s ON s.id = si.shipment_id
                WHERE s.status = 'DRAFT'
                GROUP BY si.lot_id, si.source_position_id
            ) r ON r.lot_id = b.lot_id AND r.source_position_id = b.position_id
            WHERE l.status <> 'SHIPPED'
              AND b.balance - COALESCE(r.reserved, 0) > 0
              AND (CAST(:producerId AS BIGINT) IS NULL OR l.producer_id = CAST(:producerId AS BIGINT))
              AND (CAST(:cropYear AS INTEGER) IS NULL OR l.crop_year = CAST(:cropYear AS INTEGER))
            ORDER BY l.crop_year ASC, l.received_on ASC, l.code ASC, p.code ASC
            """, nativeQuery = true)
    List<AvailableStock> findAvailableFifo(@Param("producerId") Long producerId,
                                           @Param("cropYear") Integer cropYear);
}
