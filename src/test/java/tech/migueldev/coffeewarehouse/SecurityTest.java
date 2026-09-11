package tech.migueldev.coffeewarehouse;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tech.migueldev.coffeewarehouse.api.dto.InboundRequest;
import tech.migueldev.coffeewarehouse.api.dto.LoginRequest;
import tech.migueldev.coffeewarehouse.api.dto.LotRequest;
import tech.migueldev.coffeewarehouse.domain.AppUser;
import tech.migueldev.coffeewarehouse.domain.Lot;
import tech.migueldev.coffeewarehouse.domain.Producer;
import tech.migueldev.coffeewarehouse.domain.StoragePosition;
import tech.migueldev.coffeewarehouse.domain.UserRole;
import tech.migueldev.coffeewarehouse.domain.Warehouse;
import tech.migueldev.coffeewarehouse.repository.AppUserRepository;
import tech.migueldev.coffeewarehouse.repository.LotRepository;
import tech.migueldev.coffeewarehouse.repository.ProducerRepository;
import tech.migueldev.coffeewarehouse.repository.StoragePositionRepository;
import tech.migueldev.coffeewarehouse.repository.WarehouseRepository;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Whether the policy itself is right.
 *
 * <h2>Why this suite uses real tokens</h2>
 *
 * Every other controller suite runs on {@code @WithMockUser}, which places an
 * authentication straight into the context and is exactly right there: those
 * tests are about business rules and should not restate the login flow twenty
 * times over.
 *
 * It would be the wrong tool here. {@code @WithMockUser} skips the bearer
 * filter, the decoder, the issuer validator and the claim-to-authority
 * conversion -- which is most of what this suite exists to check. So these tests
 * log in over HTTP and send the token they get back, which is the path a real
 * client takes.
 */
@AutoConfigureMockMvc
class SecurityTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private ProducerRepository producerRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private StoragePositionRepository positionRepository;

    @Autowired
    private LotRepository lotRepository;

    private Lot lot;
    private StoragePosition position;

    @BeforeEach
    void seed() {
        users.save(new AppUser("warehouse.admin", passwordEncoder.encode("admin-secret"),
                "Warehouse Administrator", UserRole.ADMIN));
        users.save(new AppUser("floor.operator", passwordEncoder.encode("operator-secret"),
                "Floor Operator", UserRole.OPERATOR));

        Producer producer = producerRepository.save(
                new Producer("COP-001", "Cooperativa Serra Alta", "Guaxupe", "MG"));
        Warehouse warehouse = warehouseRepository.save(
                new Warehouse("WH1", "Armazem Central", "Guaxupe", "MG"));
        position = positionRepository.save(
                new StoragePosition(warehouse, "01", "01", "01", new BigDecimal("60000.000")));
        lot = lotRepository.save(new Lot("LOT-001", producer, 2025,
                new BigDecimal("18000.000"), LocalDate.of(2025, 6, 10)));
    }

    private String tokenFor(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(username, password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }

    private static MockHttpServletRequestBuilder bearing(MockHttpServletRequestBuilder request,
                                                         String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private String newLotBody(String code) throws Exception {
        return objectMapper.writeValueAsString(new LotRequest(
                code, producerRepository.findAll().get(0).getId(), 2025,
                new BigDecimal("1000.000"), 20, new BigDecimal("11.50"),
                "17", "T6", "HARD", LocalDate.of(2025, 6, 10)));
    }

    private String inboundBody() throws Exception {
        return objectMapper.writeValueAsString(new InboundRequest(
                lot.getId(), position.getId(), new BigDecimal("1000.000"), null, "receiving"));
    }

    // -----------------------------------------------------------------
    // No identity at all
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("without a token")
    class Anonymous {

        @Test
        @DisplayName("a protected read is refused as problem+json, not an empty 401")
        void refusesAnonymousReads() throws Exception {
            mockMvc.perform(get("/api/lots"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(
                            MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("urn:problem-type:unauthenticated"))
                    .andExpect(jsonPath("$.title").value("Authentication required"))
                    .andExpect(jsonPath("$.instance").value("/api/lots"))
                    // RFC 6750: a 401 has to say which scheme would satisfy it.
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        }

        @Test
        @DisplayName("a protected write is refused")
        void refusesAnonymousWrites() throws Exception {
            mockMvc.perform(post("/api/movements/inbound")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(inboundBody()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("login, health and the API description stay open")
        void leavesThePublicPathsOpen() throws Exception {
            mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
            mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new LoginRequest("warehouse.admin", "admin-secret"))))
                    .andExpect(status().isOk());
        }
    }

    // -----------------------------------------------------------------
    // A token that is not acceptable
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("with a token the API will not take")
    class BadTokens {

        @Test
        @DisplayName("refuses a string that is not a token")
        void refusesGarbage() throws Exception {
            mockMvc.perform(bearing(get("/api/lots"), "not-a-token"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:unauthenticated"));
        }

        /**
         * Signed by this application, with the right issuer, and still refused --
         * which is the only interesting kind of expiry test. A token whose
         * signature was also wrong would pass for the wrong reason.
         */
        @Test
        @DisplayName("refuses a properly signed token that has expired")
        void refusesAnExpiredToken() throws Exception {
            Instant longAgo = Instant.now().minusSeconds(7200);
            JwtClaimsSet expired = JwtClaimsSet.builder()
                    .issuer("coffee-warehouse-api")
                    .subject("warehouse.admin")
                    .issuedAt(longAgo)
                    .expiresAt(longAgo.plusSeconds(60))
                    .claim("roles", List.of("ROLE_ADMIN"))
                    .build();

            String token = jwtEncoder.encode(JwtEncoderParameters.from(
                    JwsHeader.with(MacAlgorithm.HS256).build(), expired)).getTokenValue();

            mockMvc.perform(bearing(get("/api/lots"), token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.type").value("urn:problem-type:unauthenticated"));
        }

        /**
         * The one that matters most: a caller cannot promote themselves by
         * writing ROLE_ADMIN into a token they signed with a key they guessed.
         */
        @Test
        @DisplayName("refuses a token signed with the wrong key, however good its claims")
        void refusesAForeignSignature() throws Exception {
            String admin = tokenFor("floor.operator", "operator-secret");
            // Flip the signature, leaving the header and payload intact.
            String tampered = admin.substring(0, admin.lastIndexOf('.') + 1) + "AAAAAAAA";

            mockMvc.perform(bearing(post("/api/lots"), tampered)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(newLotBody("LOT-EVIL")))
                    .andExpect(status().isUnauthorized());
        }
    }

    // -----------------------------------------------------------------
    // The role matrix
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("as an operator")
    class AsOperator {

        @Test
        @DisplayName("may record a movement")
        void mayMoveStock() throws Exception {
            mockMvc.perform(bearing(post("/api/movements/inbound"),
                            tokenFor("floor.operator", "operator-secret"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(inboundBody()))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("may read the registry")
        void mayRead() throws Exception {
            mockMvc.perform(bearing(get("/api/lots"),
                            tokenFor("floor.operator", "operator-secret")))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("may not create a lot, and is told which role would")
        void mayNotMaintainTheRegistry() throws Exception {
            mockMvc.perform(bearing(post("/api/lots"),
                            tokenFor("floor.operator", "operator-secret"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(newLotBody("LOT-002")))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(
                            MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("urn:problem-type:forbidden"))
                    .andExpect(jsonPath("$.title").value("Insufficient role"));
        }
    }

    @Nested
    @DisplayName("as an administrator")
    class AsAdmin {

        @Test
        @DisplayName("may create a lot")
        void mayMaintainTheRegistry() throws Exception {
            mockMvc.perform(bearing(post("/api/lots"),
                            tokenFor("warehouse.admin", "admin-secret"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(newLotBody("LOT-002")))
                    .andExpect(status().isCreated());
        }

        /**
         * An administrator is not a registry-only role: the operational
         * endpoints accept them too, which is what {@code hasAnyRole} in the
         * chain says and what an admin covering a shift would expect.
         */
        @Test
        @DisplayName("may also record a movement")
        void mayAlsoMoveStock() throws Exception {
            mockMvc.perform(bearing(post("/api/movements/inbound"),
                            tokenFor("warehouse.admin", "admin-secret"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(inboundBody()))
                    .andExpect(status().isCreated());
        }
    }
}
