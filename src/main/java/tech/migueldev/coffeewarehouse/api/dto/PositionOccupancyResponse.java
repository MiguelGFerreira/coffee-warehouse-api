package tech.migueldev.coffeewarehouse.api.dto;

import tech.migueldev.coffeewarehouse.domain.StoragePosition;

import java.math.BigDecimal;

/**
 * How full a position is, computed from the ledger at the moment of the request.
 * There is no occupancy column to read; there is only the history.
 */
public record PositionOccupancyResponse(
        Long positionId,
        String code,
        BigDecimal capacityKg,
        BigDecimal occupiedKg,
        BigDecimal availableKg
) {

    public static PositionOccupancyResponse of(StoragePosition position, BigDecimal occupiedKg) {
        return new PositionOccupancyResponse(
                position.getId(),
                position.getCode(),
                position.getCapacityKg(),
                occupiedKg,
                position.getCapacityKg().subtract(occupiedKg));
    }
}
