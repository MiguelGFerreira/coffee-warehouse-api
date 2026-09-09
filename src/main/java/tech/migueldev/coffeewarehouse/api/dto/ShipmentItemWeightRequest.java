package tech.migueldev.coffeewarehouse.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Only the weight of a line can be revised. Pointing it at a different lot or a
 * different position is a different instruction, so it is a different line.
 */
public record ShipmentItemWeightRequest(

        @NotNull
        @DecimalMin(value = "0.0", inclusive = false, message = "must be greater than zero")
        @Digits(integer = 9, fraction = 3)
        BigDecimal weightKg
) {
}
