package tech.migueldev.coffeewarehouse.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Payload to create a storage position. There is no code field: the composite
 * code is derived from the warehouse and the address, and accepting one from the
 * caller would let it disagree with the address it is supposed to describe.
 */
public record StoragePositionRequest(

        @NotNull
        Long warehouseId,

        @NotBlank
        @Size(max = 10)
        @Pattern(regexp = "^[A-Za-z0-9]+$", message = "must be alphanumeric")
        String aisle,

        @NotBlank
        @Size(max = 10)
        @Pattern(regexp = "^[A-Za-z0-9]+$", message = "must be alphanumeric")
        String bay,

        @NotBlank
        @Size(max = 10)
        @Pattern(regexp = "^[A-Za-z0-9]+$", message = "must be alphanumeric")
        String level,

        @NotNull
        @DecimalMin(value = "0.0", inclusive = false, message = "must be greater than zero")
        @Digits(integer = 9, fraction = 3)
        BigDecimal capacityKg
) {
}
