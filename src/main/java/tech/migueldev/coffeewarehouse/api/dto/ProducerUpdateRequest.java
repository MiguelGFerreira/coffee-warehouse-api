package tech.migueldev.coffeewarehouse.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload to update a producer. Deliberately without the code field: a request
 * that cannot express a code change is clearer than one that silently ignores it.
 */
public record ProducerUpdateRequest(

        @NotBlank
        @Size(max = 150)
        String name,

        @Size(max = 100)
        String city,

        @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be a two-letter state code")
        String state
) {
}
