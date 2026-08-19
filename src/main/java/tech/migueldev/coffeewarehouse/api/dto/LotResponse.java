package tech.migueldev.coffeewarehouse.api.dto;

import tech.migueldev.coffeewarehouse.domain.Lot;
import tech.migueldev.coffeewarehouse.domain.LotStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record LotResponse(
        Long id,
        String code,
        Long producerId,
        String producerCode,
        Integer cropYear,
        BigDecimal netWeightKg,
        Integer bags,
        BigDecimal moisturePercent,
        String screenSize,
        String defectType,
        String cupQuality,
        LocalDate receivedOn,
        LotStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static LotResponse from(Lot lot) {
        return new LotResponse(
                lot.getId(),
                lot.getCode(),
                lot.getProducer().getId(),
                lot.getProducer().getCode(),
                lot.getCropYear(),
                lot.getNetWeightKg(),
                lot.getBags(),
                lot.getMoisturePercent(),
                lot.getScreenSize(),
                lot.getDefectType(),
                lot.getCupQuality(),
                lot.getReceivedOn(),
                lot.getStatus(),
                lot.getCreatedAt(),
                lot.getUpdatedAt());
    }
}
