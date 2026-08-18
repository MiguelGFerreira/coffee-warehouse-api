package tech.migueldev.coffeewarehouse.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload to create a warehouse. A warehouse is always born active; the active
 * flag moves only through the dedicated activate/deactivate endpoints.
 */
public record WarehouseRequest(

        @NotBlank
        @Size(max = 10)
        @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "must contain only letters, digits, dot, dash or underscore")
        String code,

        @NotBlank
        @Size(max = 120)
        String name,

        @Size(max = 100)
        String city,

        @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be a two-letter state code")
        String state
) {
}
