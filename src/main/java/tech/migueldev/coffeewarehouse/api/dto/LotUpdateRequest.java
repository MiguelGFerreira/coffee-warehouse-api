package tech.migueldev.coffeewarehouse.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Only the classification can be revised: samples get re-graded and bag counts
 * get corrected. Weight, crop year, producer and receiving date are fixed at
 * creation, and status belongs to the ledger.
 */
public record LotUpdateRequest(

        @Positive
        Integer bags,

        @DecimalMin("0.0")
        @DecimalMax("100.0")
        @Digits(integer = 3, fraction = 2)
        BigDecimal moisturePercent,

        @Size(max = 10)
        String screenSize,

        @Size(max = 10)
        String defectType,

        @Size(max = 30)
        String cupQuality
) {
}
