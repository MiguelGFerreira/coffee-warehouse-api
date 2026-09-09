package tech.migueldev.coffeewarehouse.repository;

import tech.migueldev.coffeewarehouse.domain.StockMovement;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Balances are aggregations over the ledger, never stored columns.
 *
 * Reads cost more than a counter would. What that buys is that occupancy and
 * balance cannot drift from the history that produced them, and that the
 * audit trail is the source of truth rather than a parallel table.
 */
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    /**
     * Total weight sitting in a position, across every lot: what came in minus
     * what went out. A transfer contributes to both sides of the warehouse but
     * to only one side of this position, which is exactly what the two CASEs say.
     */
    @Query("""
            SELECT COALESCE(SUM(
                       CASE WHEN m.targetPosition.id = :positionId THEN m.weightKg ELSE 0 END
                     - CASE WHEN m.sourcePosition.id = :positionId THEN m.weightKg ELSE 0 END), 0)
            FROM StockMovement m
            WHERE m.targetPosition.id = :positionId OR m.sourcePosition.id = :positionId
            """)
    BigDecimal occupancyOf(@Param("positionId") Long positionId);

    /**
     * How much of one lot is available at one position -- the number a transfer
     * or an outbound has to respect at its source.
     */
    @Query("""
            SELECT COALESCE(SUM(
                       CASE WHEN m.targetPosition.id = :positionId THEN m.weightKg ELSE 0 END
                     - CASE WHEN m.sourcePosition.id = :positionId THEN m.weightKg ELSE 0 END), 0)
            FROM StockMovement m
            WHERE m.lot.id = :lotId
              AND (m.targetPosition.id = :positionId OR m.sourcePosition.id = :positionId)
            """)
    BigDecimal balanceOfLotAt(@Param("lotId") Long lotId, @Param("positionId") Long positionId);

    /**
     * Total weight of a lot still stored anywhere. A transfer nets to zero here
     * by construction -- plus at the target, minus at the source -- so the same
     * expression serves without branching on the movement type.
     */
    @Query("""
            SELECT COALESCE(SUM(
                       CASE WHEN m.targetPosition.id IS NOT NULL THEN m.weightKg ELSE 0 END
                     - CASE WHEN m.sourcePosition.id IS NOT NULL THEN m.weightKg ELSE 0 END), 0)
            FROM StockMovement m
            WHERE m.lot.id = :lotId
            """)
    BigDecimal balanceOfLot(@Param("lotId") Long lotId);

    /**
     * Everything ever received for a lot, which is the number its net weight
     * caps -- deliberately not the current balance.
     *
     * A lot that came in at 12,000 kg and shipped out entirely has a balance of
     * zero, but that coffee is gone: it cannot be received a second time. The
     * ceiling is on what has cumulatively arrived, so a lot put away in parts
     * across several positions still adds up to exactly its net weight and no
     * more.
     */
    @Query("""
            SELECT COALESCE(SUM(m.weightKg), 0)
            FROM StockMovement m
            WHERE m.lot.id = :lotId
              AND m.type = tech.migueldev.coffeewarehouse.domain.MovementType.INBOUND
            """)
    BigDecimal totalInboundOf(@Param("lotId") Long lotId);

    @Override
    @EntityGraph(attributePaths = {"lot", "sourcePosition", "targetPosition"})
    Optional<StockMovement> findById(Long id);

    @EntityGraph(attributePaths = {"lot", "sourcePosition", "targetPosition"})
    List<StockMovement> findByLotIdOrderByOccurredAtAscIdAsc(Long lotId);
}
