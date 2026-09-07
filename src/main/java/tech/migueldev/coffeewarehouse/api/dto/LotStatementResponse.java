package tech.migueldev.coffeewarehouse.api.dto;

import tech.migueldev.coffeewarehouse.domain.Lot;
import tech.migueldev.coffeewarehouse.domain.LotStatus;
import tech.migueldev.coffeewarehouse.domain.StockMovement;

import java.math.BigDecimal;
import java.util.List;

/**
 * The history of a lot and the balance that history produces.
 *
 * The balance is not a stored number being reported: it is the sum of the
 * entries listed right below it, which is what makes the statement checkable
 * by hand.
 */
public record LotStatementResponse(
        Long lotId,
        String lotCode,
        LotStatus status,
        BigDecimal netWeightKg,
        BigDecimal storedWeightKg,
        List<StockMovementResponse> entries
) {

    public static LotStatementResponse of(Lot lot, BigDecimal storedWeightKg,
                                          List<StockMovement> movements) {
        return new LotStatementResponse(
                lot.getId(),
                lot.getCode(),
                lot.getStatus(),
                lot.getNetWeightKg(),
                storedWeightKg,
                movements.stream().map(StockMovementResponse::from).toList());
    }
}
