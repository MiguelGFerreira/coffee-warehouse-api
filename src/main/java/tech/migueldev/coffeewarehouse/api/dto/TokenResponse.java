package tech.migueldev.coffeewarehouse.api.dto;

import tech.migueldev.coffeewarehouse.domain.UserRole;

/**
 * A freshly minted token and the little a client needs to use it.
 *
 * The shape echoes an OAuth2 token response without claiming to be one: this is
 * not an authorization-code flow, there is no client registration and there is
 * no refresh token, so borrowing the field names while pretending to the
 * protocol would be worse than the resemblance is worth.
 *
 * The role and display name are returned so a client can render a menu without
 * decoding the token itself. They are a convenience, never an authority: what
 * the API enforces is what the signed token says, not what the client believed.
 *
 * @param expiresInSeconds relative rather than an absolute instant, so a client
 *                         with a skewed clock still schedules its renewal right
 */
public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        String username,
        String displayName,
        UserRole role
) {

    /** The scheme the Authorization header must use: {@code Bearer <token>}. */
    public static final String BEARER = "Bearer";
}
