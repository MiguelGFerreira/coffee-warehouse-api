package tech.migueldev.coffeewarehouse.api.dto;

import tech.migueldev.coffeewarehouse.domain.StoragePosition;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record StoragePositionResponse(
        Long id,
        String code,
        Long warehouseId,
        String warehouseCode,
        String aisle,
        String bay,
        String level,
        BigDecimal capacityKg,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static StoragePositionResponse from(StoragePosition position) {
        return new StoragePositionResponse(
                position.getId(),
                position.getCode(),
                position.getWarehouse().getId(),
                position.getWarehouse().getCode(),
                position.getAisle(),
                position.getBay(),
                position.getLevel(),
                position.getCapacityKg(),
                position.isActive(),
                position.getCreatedAt(),
                position.getUpdatedAt());
    }
}
