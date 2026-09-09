package tech.migueldev.coffeewarehouse.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * One picking instruction: this much of this lot, out of this position.
 *
 * The position is required rather than resolved for the caller. A lot can sit
 * in several positions, and picking one on the caller's behalf would be the
 * suggestion endpoint's job, not this one's -- here the choice is already made.
 */
public record ShipmentItemRequest(

        @NotNull
        Long lotId,

        @NotNull
        Long sourcePositionId,

        @NotNull
        @DecimalMin(value = "0.0", inclusive = false, message = "must be greater than zero")
        @Digits(integer = 9, fraction = 3)
        BigDecimal weightKg
) {
}
