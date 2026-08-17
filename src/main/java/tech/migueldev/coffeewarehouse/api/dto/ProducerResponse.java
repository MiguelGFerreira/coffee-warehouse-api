package tech.migueldev.coffeewarehouse.api.dto;

import tech.migueldev.coffeewarehouse.domain.Producer;

import java.time.OffsetDateTime;

public record ProducerResponse(
        Long id,
        String code,
        String name,
        String city,
        String state,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static ProducerResponse from(Producer producer) {
        return new ProducerResponse(
                producer.getId(),
                producer.getCode(),
                producer.getName(),
                producer.getCity(),
                producer.getState(),
                producer.getCreatedAt(),
                producer.getUpdatedAt());
    }
}
