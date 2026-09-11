package tech.migueldev.coffeewarehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tech.migueldev.coffeewarehouse.api.dto.LoginRequest;
import tech.migueldev.coffeewarehouse.domain.AppUser;
import tech.migueldev.coffeewarehouse.domain.UserRole;
import tech.migueldev.coffeewarehouse.repository.AppUserRepository;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

/**
 * Exchanging credentials for a token.
 *
 * Deliberately without a class-level {@code @WithMockUser}: the point of the
 * login endpoint is that it works for a caller who has no identity yet, and
 * handing this suite one would test a different endpoint than the one shipped.
 */
@AutoConfigureMockMvc
class AuthControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @BeforeEach
    void seedUsers() {
        users.save(new AppUser("warehouse.admin", passwordEncoder.encode("admin-secret"),
                "Warehouse Administrator", UserRole.ADMIN));
        users.save(new AppUser("floor.operator", passwordEncoder.encode("operator-secret"),
                "Floor Operator", UserRole.OPERATOR));

        AppUser retired = new AppUser("retired.operator", passwordEncoder.encode("still-knows-it"),
                "Retired Operator", UserRole.OPERATOR);
        retired.deactivate();
        users.save(retired);
    }

    private String login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(username, password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("returns a bearer token and the identity behind it")
    void returnsAToken() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("warehouse.admin", "admin-secret"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(28800))
                .andExpect(jsonPath("$.username").value("warehouse.admin"))
                .andExpect(jsonPath("$.displayName").value("Warehouse Administrator"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                // Whatever else it returns, it never returns the credential.
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("signs the token with the claims the filter chain reads back")
    void signsTheExpectedClaims() throws Exception {
        String body = login("floor.operator", "operator-secret");
        String token = objectMapper.readTree(body).get("accessToken").asText();

        // Decoding with the application's own decoder proves the signature and
        // the issuer are what the resource server will accept -- asserting on
        // the raw string would only prove something was returned.
        Jwt jwt = jwtDecoder.decode(token);

        assertThat(jwt.getSubject()).isEqualTo("floor.operator");
        // Read as a plain claim, not via getIssuer(): RFC 7519 defines iss as a
        // StringOrURI and this one is a name, while Jwt.getIssuer() insists on
        // converting to a URL. The decoder validates it either way -- see the
        // issuer validator wired in SecurityConfig.
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("coffee-warehouse-api");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ROLE_OPERATOR");
        assertThat(jwt.getClaimAsString("name")).isEqualTo("Floor Operator");
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
    }

    /**
     * The issuer claim is only worth writing if something reads it. A correctly
     * signed token from a different issuer is forged as far as this API is
     * concerned -- another service sharing the secret, a staging environment
     * pointed at the same configuration -- and the decoder has to say so. This
     * fails if the issuer validator is dropped from SecurityConfig, which is the
     * only reason it earns its place.
     */
    @Test
    @DisplayName("refuses a correctly signed token minted by a different issuer")
    void refusesAForeignIssuer() throws Exception {
        Instant now = Instant.now();
        JwtClaimsSet foreign = JwtClaimsSet.builder()
                .issuer("some-other-service")
                .subject("warehouse.admin")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .claim("roles", List.of("ROLE_ADMIN"))
                .build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), foreign)).getTokenValue();

        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    @DisplayName("normalizes the username, so case is not a second account")
    void normalizesTheUsername() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("Warehouse.Admin", "admin-secret"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("warehouse.admin"));
    }

    @Test
    @DisplayName("refuses a wrong password as problem+json, not a stack trace")
    void refusesAWrongPassword() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("warehouse.admin", "not-the-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:problem-type:authentication-failed"))
                .andExpect(jsonPath("$.detail").value("Invalid username or password"));
    }

    /**
     * The three failures a login can have must be indistinguishable, or the
     * endpoint becomes an oracle for which usernames exist. This asserts they
     * are byte-for-byte the same answer, which is the only way to notice if a
     * future change starts being helpful.
     */
    @Test
    @DisplayName("answers identically for a wrong password, an unknown user and a disabled account")
    void tellsAnAttackerNothing() throws Exception {
        String wrongPassword = failedLogin("warehouse.admin", "not-the-password");
        String unknownUser = failedLogin("nobody.here", "not-the-password");
        String disabledAccount = failedLogin("retired.operator", "still-knows-it");

        assertThat(unknownUser).isEqualTo(wrongPassword);
        assertThat(disabledAccount).isEqualTo(wrongPassword);
    }

    private String failedLogin(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(username, password))))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("rejects a blank credential as a validation error")
    void rejectsABlankCredential() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem-type:validation-failed"));
    }
}
