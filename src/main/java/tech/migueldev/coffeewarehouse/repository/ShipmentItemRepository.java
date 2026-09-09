package tech.migueldev.coffeewarehouse.repository;

import tech.migueldev.coffeewarehouse.domain.ShipmentItem;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

/**
 * Reserved weight is an aggregation, never a column.
 *
 * This is the same decision as occupancy, applied a second time. Nothing
 * physically moves when a lot is put on a draft shipment, so its ledger balance
 * is untouched -- which is exactly why two shipments could otherwise each claim
 * the same 10,000 kg. What is spoken for is the sum of the draft items pointing
 * at it, so the claim and the stock cannot drift apart.
 */
public interface ShipmentItemRepository extends JpaRepository<ShipmentItem, Long> {

    /**
     * How much of a lot, at one position, is already committed to some *other*
     * draft shipment.
     *
     * The exclusion is what makes the check work when a line is being added to
     * or revised on a shipment that already holds some of the same lot: the
     * caller compares the line's new total against this, so counting the
     * shipment's own current line here would charge it twice for weight it is
     * about to replace. Pass null to ask about every draft shipment.
     *
     * Only DRAFT counts. A confirmed shipment has already taken its weight out
     * through the ledger, so charging it again would hide stock that is
     * genuinely gone; a cancelled one never took anything.
     */
    @Query("""
            SELECT COALESCE(SUM(i.weightKg), 0)
            FROM ShipmentItem i
            WHERE i.lot.id = :lotId
              AND i.sourcePosition.id = :positionId
              AND i.shipment.status = tech.migueldev.coffeewarehouse.domain.ShipmentStatus.DRAFT
              AND (:excludingShipmentId IS NULL OR i.shipment.id <> :excludingShipmentId)
            """)
    BigDecimal reservedOfLotAt(@Param("lotId") Long lotId,
                               @Param("positionId") Long positionId,
                               @Param("excludingShipmentId") Long excludingShipmentId);

    /**
     * Whether any draft shipment still holds this lot, which is what decides
     * if removing a line takes the lot out of RESERVED.
     */
    @Query("""
            SELECT COUNT(i) > 0
            FROM ShipmentItem i
            WHERE i.lot.id = :lotId
              AND i.shipment.status = tech.migueldev.coffeewarehouse.domain.ShipmentStatus.DRAFT
              AND (:excludingShipmentId IS NULL OR i.shipment.id <> :excludingShipmentId)
            """)
    boolean existsDraftReservationForLot(@Param("lotId") Long lotId,
                                         @Param("excludingShipmentId") Long excludingShipmentId);
}
