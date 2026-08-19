package tech.migueldev.coffeewarehouse.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Payload to create a lot. There is no status field: a lot enters the registry
 * as AWAITING_ALLOCATION and moves only through the movement ledger, in Phase 3.
 * Letting a client post a status would make the ledger and the column disagree.
 */
public record LotRequest(

        @NotBlank
        @Size(max = 30)
        @Pattern(regexp = "^[A-Za-z0-9._/-]+$", message = "must contain only letters, digits, dot, dash, slash or underscore")
        String code,

        @NotNull
        Long producerId,

        @NotNull
        @Min(1900)
        @Max(2200)
        Integer cropYear,

        @NotNull
        @DecimalMin(value = "0.0", inclusive = false, message = "must be greater than zero")
        @Digits(integer = 9, fraction = 3)
        BigDecimal netWeightKg,

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
        String cupQuality,

        @NotNull
        @PastOrPresent
        LocalDate receivedOn
) {
}
