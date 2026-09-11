package tech.migueldev.coffeewarehouse.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Credentials offered in exchange for a token.
 *
 * The constraints are deliberately loose -- present, and within the column
 * width. A login endpoint that reported "password must contain a digit" would
 * be describing the stored password to whoever asked.
 */
public record LoginRequest(
        @NotBlank
        @Size(max = 60)
        String username,
        @NotBlank
        @Size(max = 100)
        String password
) {
}
