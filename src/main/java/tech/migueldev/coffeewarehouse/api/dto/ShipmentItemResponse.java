package tech.migueldev.coffeewarehouse.api.dto;

import tech.migueldev.coffeewarehouse.domain.ShipmentItem;

import java.math.BigDecimal;

public record ShipmentItemResponse(
        Long id,
        Long lotId,
        String lotCode,
        Long sourcePositionId,
        String sourcePositionCode,
        BigDecimal weightKg
) {

    public static ShipmentItemResponse from(ShipmentItem item) {
        return new ShipmentItemResponse(
                item.getId(),
                item.getLot().getId(),
                item.getLot().getCode(),
                item.getSourcePosition().getId(),
                item.getSourcePosition().getCode(),
                item.getWeightKg());
    }
}
