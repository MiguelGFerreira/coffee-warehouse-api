package tech.migueldev.coffeewarehouse.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;

/**
 * Who may call what, and how a token is signed and checked.
 *
 * <h2>Why the rules are request matchers and not {@code @PreAuthorize}</h2>
 *
 * Method security on the services would scatter the policy across the layer
 * that holds business invariants, and it would break {@code
 * LedgerConcurrencyTest} -- which drives {@code StockMovementService} straight
 * from a thread pool, where the {@code SecurityContext} does not propagate to
 * the worker threads. That test is the best evidence in the repository that the
 * capacity invariant survives a race; it should not have to know that
 * authentication exists.
 *
 * Keeping the whole policy in one chain also means it can be read at a glance,
 * which is the property that makes it reviewable. The honest limit: a rule that
 * depended on the <em>contents</em> of a request rather than its path would not
 * fit here and would have to move to method security. None currently does.
 *
 * <h2>Why stateless, and why CSRF is off</h2>
 *
 * Nothing is kept between requests: the token carries the identity and the
 * server stores no session. CSRF protection defends a browser that sends an
 * ambient credential -- a cookie -- without the page meaning to. An
 * {@code Authorization} header is never sent ambiently, so there is no
 * cross-site request to forge, and a CSRF token would be ceremony guarding a
 * door that does not exist.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    /** Paths a caller reaches before it has any identity at all. */
    private static final String[] PUBLIC_PATHS = {
            "/api/auth/login",
            "/docs", "/docs/**", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**",
            "/actuator/health", "/actuator/health/**", "/actuator/info"
    };

    /** Maintaining the registry is not an operational act. */
    private static final String[] REGISTRY_PATHS = {
            "/api/producers/**", "/api/warehouses/**",
            "/api/storage-positions/**", "/api/lots/**"
    };

    /** Working the warehouse floor. */
    private static final String[] OPERATIONS_PATHS = {
            "/api/movements/**", "/api/shipments/**"
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder)
            throws Exception {

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // Reading comes before the role rules on purpose: the
                        // first matching rule wins, so this one grants every
                        // authenticated caller read access to the whole API and
                        // leaves the rules below governing writes only.
                        .requestMatchers(HttpMethod.GET, "/api/**").authenticated()
                        .requestMatchers(OPERATIONS_PATHS).hasAnyRole("OPERATOR", "ADMIN")
                        .requestMatchers(REGISTRY_PATHS).hasRole("ADMIN")
                        // Anything not named above -- a new endpoint, a stray
                        // actuator path -- is closed rather than open. A policy
                        // whose default is permitAll leaks by omission.
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .decoder(jwtDecoder)
                        .jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable());

        return http.build();
    }

    /**
     * Reads the authorities straight out of the {@code roles} claim.
     *
     * The default converter looks at {@code scope} and prefixes every value with
     * {@code SCOPE_}, which is the OAuth2 delegation model. This API issues its
     * own tokens for its own users, so the claim holds the authority name as
     * Spring Security spells it ({@code ROLE_ADMIN}) and no prefix is added on
     * top of it -- see {@code UserRole.authority()}.
     */
    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /**
     * The login endpoint's way in. Built explicitly rather than pulled from
     * {@code AuthenticationConfiguration} so that what authenticates a user --
     * this {@link UserDetailsService} against this {@link PasswordEncoder} -- is
     * visible here instead of assembled by autoconfiguration somewhere else.
     */
    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey) {
        return NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * One key, signing and verifying, because one service does both. Asymmetric
     * keys buy the ability to let a third party verify a token without being
     * able to mint one; there is no third party here, and RS256 would be
     * key management bought for a guarantee nobody uses.
     *
     * A secret shorter than 256 bits brings the application down at startup with
     * a message saying so. The alternative -- padding it, or falling back to a
     * generated key -- would leave a deployment believing it is signing tokens
     * properly when it is not.
     */
    @Bean
    SecretKey jwtSigningKey(JwtProperties properties) {
        byte[] keyBytes = properties.secret() == null
                ? new byte[0]
                : properties.secret().getBytes(StandardCharsets.UTF_8);

        if (keyBytes.length < JwtProperties.MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException(
                    ("security.jwt.secret must be at least %d bytes for HS256; it is %d. "
                            + "Set a longer value, for example through the JWT_SECRET "
                            + "environment variable.")
                            .formatted(JwtProperties.MINIMUM_SECRET_BYTES, keyBytes.length));
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }
}
