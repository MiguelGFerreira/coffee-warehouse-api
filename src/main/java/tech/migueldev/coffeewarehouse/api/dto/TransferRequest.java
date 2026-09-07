package tech.migueldev.coffeewarehouse.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Weight moving between two positions. Source and target must differ, which is
 * checked in the service -- Bean Validation cannot compare two fields of the
 * same record without a custom validator, and the schema CHECK is the backstop.
 */
public record TransferRequest(

        @NotNull
        Long lotId,

        @NotNull
        Long sourcePositionId,

        @NotNull
        Long targetPositionId,

        @NotNull
        @DecimalMin(value = "0.0", inclusive = false, message = "must be greater than zero")
        @Digits(integer = 9, fraction = 3)
        BigDecimal weightKg,

        @PastOrPresent
        OffsetDateTime occurredAt,

        @Size(max = 200)
        String reason
) {
}
