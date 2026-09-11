package tech.migueldev.coffeewarehouse.api.controller;

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
    @PostMapping("/login")
    public TokenResponse login(@RequestBody @Valid LoginRequest request) {
        return service.login(request);
    }
}
