package tech.migueldev.coffeewarehouse.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload to create a producer. The code is only accepted here: it is the
 * business identity of the producer and never changes afterwards.
 */
public record ProducerRequest(

        @NotBlank
        @Size(max = 20)
        @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "must contain only letters, digits, dot, dash or underscore")
        String code,

        @NotBlank
        @Size(max = 150)
        String name,

        @Size(max = 100)
        String city,

        @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be a two-letter state code")
        String state
) {
}
