package tech.migueldev.coffeewarehouse.api.dto;

import tech.migueldev.coffeewarehouse.domain.MovementType;
import tech.migueldev.coffeewarehouse.domain.StockMovement;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record StockMovementResponse(
        Long id,
        MovementType type,
        Long lotId,
        String lotCode,
        String sourcePositionCode,
        String targetPositionCode,
        BigDecimal weightKg,
        OffsetDateTime occurredAt,
        String reason
) {

    public static StockMovementResponse from(StockMovement movement) {
        return new StockMovementResponse(
                movement.getId(),
                movement.getType(),
                movement.getLot().getId(),
                movement.getLot().getCode(),
                movement.getSourcePosition() == null ? null : movement.getSourcePosition().getCode(),
                movement.getTargetPosition() == null ? null : movement.getTargetPosition().getCode(),
                movement.getWeightKg(),
                movement.getOccurredAt(),
                movement.getReason());
    }
}
