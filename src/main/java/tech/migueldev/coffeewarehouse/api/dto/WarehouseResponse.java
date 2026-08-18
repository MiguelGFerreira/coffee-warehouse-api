package tech.migueldev.coffeewarehouse.api.dto;

import tech.migueldev.coffeewarehouse.domain.Warehouse;

import java.time.OffsetDateTime;

public record WarehouseResponse(
        Long id,
        String code,
        String name,
        String city,
        String state,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static WarehouseResponse from(Warehouse warehouse) {
        return new WarehouseResponse(
                warehouse.getId(),
                warehouse.getCode(),
                warehouse.getName(),
                warehouse.getCity(),
                warehouse.getState(),
                warehouse.isActive(),
                warehouse.getCreatedAt(),
                warehouse.getUpdatedAt());
    }
}
