package tech.migueldev.coffeewarehouse.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload to update a warehouse. Without the code, which is immutable, and
 * without the active flag, which is a state transition and not an edit.
 */
public record WarehouseUpdateRequest(

        @NotBlank
        @Size(max = 120)
        String name,

        @Size(max = 100)
        String city,

        @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be a two-letter state code")
        String state
) {
}
