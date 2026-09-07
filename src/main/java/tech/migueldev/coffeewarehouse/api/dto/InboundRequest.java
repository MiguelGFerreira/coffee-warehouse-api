package tech.migueldev.coffeewarehouse.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Weight arriving into a position. No source, by definition -- the schema
 * enforces the same shape with a CHECK.
 *
 * {@code occurredAt} is optional and defaults to now: a movement is usually
 * recorded as it happens, but a backdated entry has to be expressible.
 */
public record InboundRequest(

        @NotNull
        Long lotId,

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
