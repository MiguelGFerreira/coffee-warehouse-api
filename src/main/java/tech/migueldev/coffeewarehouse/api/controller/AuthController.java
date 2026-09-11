package tech.migueldev.coffeewarehouse.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import tech.migueldev.coffeewarehouse.api.dto.LoginRequest;
import tech.migueldev.coffeewarehouse.api.dto.TokenResponse;
import tech.migueldev.coffeewarehouse.service.AuthenticationService;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one door into the API that does not need a token.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Exchanging credentials for a bearer token")
// Clears the global bearer requirement: this is the one place a caller arrives
// without a token, and documenting it as protected would be a chicken and egg.
@SecurityRequirements
public class AuthController {

    private final AuthenticationService service;

    public AuthController(AuthenticationService service) {
        this.service = service;
    }

    /**
     * Exchanges credentials for a bearer token.
     *
     * <p>A POST even though it creates no resource: the credentials belong in a
     * body, and a GET would put a password in the request line, where it lands
     * in access logs, proxy caches and browser history.
     *
     * <p>200 rather than 201 for the same reason -- nothing was created, and
     * there is no URL to point a {@code Location} header at.
     */
    @Operation(
            summary = "Log in and receive a bearer token",
            description = """
                    Returns a signed JWT valid for 8 hours, plus the role behind it so a \
                    client can render its menu without decoding the token.

                    The seeded development accounts are `warehouse.admin` / `admin123` \
                    (ADMIN) and `floor.operator` / `operator123` (OPERATOR).

                    A wrong password, an unknown username and a deactivated account all \
                    return exactly the same 401. That is deliberate: distinguishing them \
                    would turn this endpoint into an oracle for which usernames exist.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Credentials accepted"),
            @ApiResponse(responseCode = "400",
                    description = "A credential was blank (`validation-failed`)"),
            @ApiResponse(responseCode = "401",
                    description = "Credentials refused (`authentication-failed`)")
    })
    @PostMapping("/login")
    public TokenResponse login(@RequestBody @Valid LoginRequest request) {
        return service.login(request);
    }
}
