package tech.migueldev.coffeewarehouse.repository;

import tech.migueldev.coffeewarehouse.domain.StockMovement;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Balances are aggregations over the ledger, never stored columns.
 *
 * Reads cost more than a counter would. What that buys is that occupancy and
 * balance cannot drift from the history that produced them, and that the
 * audit trail is the source of truth rather than a parallel table.
 */
public interface StockMovementRepository extends Repository<StockMovement, Long> {

    /**
     * Deliberately {@link Repository} and not {@code JpaRepository}.
     *
     * Extending the full interface would hand every caller {@code delete},
     * {@code deleteAll} and {@code deleteById} on an append-only table -- three
     * methods that must never be called, sitting in plain sight on the object
     * whose whole point is that history is not editable. Inheriting them and
     * trusting nobody to reach for them is not a design; declaring the surface
     * the ledger actually has is.
     *
     * The database refuses those operations too, since V4. This is the same
     * rule stated one layer up, where it is cheaper to notice: a compile error
     * rather than a runtime exception.
     */
    /**
     * Declared with the same generic signature the JPA base implementation uses,
     * so Spring Data binds them to it instead of trying to derive a query from
     * the method name.
     */
    <S extends StockMovement> S save(S movement);

    <S extends StockMovement> S saveAndFlush(S movement);

    long count();

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

    /**
     * When this lot last moved at this position, or null if it never has.
     *
     * The ledger is append-only in time as well as in row order: nothing may be
     * recorded as having happened before something already recorded for the same
     * stock. That is what makes every other check here correct. Balance and
     * occupancy are sums over all rows -- they answer "how much is there now" --
     * so validating a movement against them is only sound if "now" is also the
     * moment the movement claims to have happened.
     *
     * Without the rule, an outbound backdated behind the inbound that supplied
     * it passes on today's balance and then prints a statement whose running
     * total dips below zero and recovers.
     */
    @Query("""
            SELECT MAX(m.occurredAt)
            FROM StockMovement m
            WHERE m.lot.id = :lotId
              AND (m.sourcePosition.id = :positionId OR m.targetPosition.id = :positionId)
            """)
    OffsetDateTime lastMovementOfLotAt(@Param("lotId") Long lotId,
                                       @Param("positionId") Long positionId);

    @EntityGraph(attributePaths = {"lot", "sourcePosition", "targetPosition"})
    Optional<StockMovement> findById(Long id);

    @EntityGraph(attributePaths = {"lot", "sourcePosition", "targetPosition"})
    List<StockMovement> findByLotIdOrderByOccurredAtAscIdAsc(Long lotId);
}
