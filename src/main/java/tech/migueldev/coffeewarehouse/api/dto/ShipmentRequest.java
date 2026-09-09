package tech.migueldev.coffeewarehouse.api.dto;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * A shipment starts empty and in DRAFT. Its composition arrives afterwards,
 * one item at a time, because each line has to be checked against the stock
 * available at the moment it is added.
 */
public record ShipmentRequest(

        @NotBlank
        @Size(max = 30)
        String code,

        @NotBlank
        @Size(max = 150)
        String destination,

        @FutureOrPresent
        LocalDate scheduledFor
) {
}
