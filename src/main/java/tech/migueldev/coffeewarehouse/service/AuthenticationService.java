package tech.migueldev.coffeewarehouse.service;

import tech.migueldev.coffeewarehouse.api.dto.LoginRequest;
import tech.migueldev.coffeewarehouse.api.dto.TokenResponse;
import tech.migueldev.coffeewarehouse.config.JwtProperties;
import tech.migueldev.coffeewarehouse.domain.AppUser;
import tech.migueldev.coffeewarehouse.service.AppUserDetailsService.AuthenticatedUser;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Checks credentials and mints the token that stands in for them afterwards.
 *
 * <h2>Why the AuthenticationManager and not a password comparison here</h2>
 *
 * Comparing the hash directly would be three lines and would quietly drop
 * everything {@code DaoAuthenticationProvider} already does: the disabled-account
 * check, the constant-time comparison, the deliberate refusal to distinguish an
 * unknown user from a wrong password, and the password re-encoding hook for when
 * the encoder's strength changes. Reimplementing that is how authentication bugs
 * are written.
 */
@Service
public class AuthenticationService {

    private final AuthenticationManager authenticationManager;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;

    public AuthenticationService(AuthenticationManager authenticationManager,
                                 JwtEncoder jwtEncoder,
                                 JwtProperties properties) {
        this.authenticationManager = authenticationManager;
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    /**
     * Any failure leaves as an {@link org.springframework.security.core.AuthenticationException},
     * which the exception handler turns into one 401 with one message. Telling a
     * caller which half of the pair was wrong -- or that the account exists but
     * is disabled -- would make this endpoint an oracle for valid usernames.
     */
    public TokenResponse login(LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));

        AppUser user = ((AuthenticatedUser) authentication.getPrincipal()).user();
        return issueFor(user);
    }

    /**
     * The claims, and why each one is there.
     *
     * <ul>
     *   <li>{@code iss} so a token minted by another service that happens to
     *       share the secret is still refused;</li>
     *   <li>{@code sub} is the username, which is the identity everywhere else
     *       in this API;</li>
     *   <li>{@code iat}/{@code exp} bound the token in time -- without them a
     *       leaked token is permanent;</li>
     *   <li>{@code roles} carries the authority as Spring Security spells it,
     *       which is what the resource-server converter reads back.</li>
     * </ul>
     *
     * <p><b>The token carries the role it had at login.</b> Changing a user's
     * role does not reach tokens already issued; they keep the old one until
     * they expire. That is the cost of statelessness, and the reason the
     * lifetime is a shift rather than a month. Closing it would mean checking
     * the database on every request, which is the session this design exists to
     * avoid.
     */
    private TokenResponse issueFor(AppUser user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.ttl());

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(user.getUsername())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("roles", List.of(user.getRole().authority()))
                .claim("name", user.getDisplayName())
                .build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        return new TokenResponse(token, TokenResponse.BEARER, properties.ttl().toSeconds(),
                user.getUsername(), user.getDisplayName(), user.getRole());
    }
}
