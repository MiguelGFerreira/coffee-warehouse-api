package tech.migueldev.coffeewarehouse.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Only the capacity can be revised. Changing the address would change the code,
 * and the code is the identity of the position -- that is a new position, not an
 * edit of this one.
 */
public record StoragePositionUpdateRequest(

        @NotNull
        @DecimalMin(value = "0.0", inclusive = false, message = "must be greater than zero")
        @Digits(integer = 9, fraction = 3)
        BigDecimal capacityKg
) {
}
