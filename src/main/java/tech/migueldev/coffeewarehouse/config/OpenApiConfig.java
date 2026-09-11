package tech.migueldev.coffeewarehouse.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The API description, written rather than inherited.
 *
 * springdoc will produce a document with no annotations at all, and that
 * document is useless in the way a schema dump is useless: it lists paths and
 * types and says nothing about what the endpoints are for or why they refuse
 * things. The interesting part of this API is the refusals, so they are what
 * gets documented.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    private static final String PROBLEM_SCHEMA = "ProblemDetail";

    private static final String PROBLEM_MEDIA_TYPE = "application/problem+json";

    private static final String JSON_MEDIA_TYPE = "application/json";

    /** What springdoc falls back to when a handler declares no {@code produces}. */
    private static final String ANY_MEDIA_TYPE = "*/*";

    /**
     * The overview, and the catalogue of problem types.
     *
     * The catalogue lives here rather than in a README because it is the part of
     * the contract a client codes against: {@code type} is a stable identifier
     * to switch on, while {@code detail} is prose that may be reworded. Having
     * it on the page where the endpoints are is what makes it get read.
     */
    @Bean
    OpenAPI coffeeWarehouseOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Coffee Warehouse API")
                        .version("0.1.0")
                        .description(description())
                        .contact(new Contact()
                                .name("Miguel G. Ferreira")
                                .url("https://github.com/MiguelGFerreira"))
                        .license(new License().name("MIT")))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("""
                                        Paste the `accessToken` returned by \
                                        `POST /api/auth/login`. Swagger sends it \
                                        as `Authorization: Bearer <token>`.""")))
                // Applied to every operation, then switched off individually on
                // the ones that are public. Declaring it the other way round
                // would mean remembering to add it to each new endpoint, and a
                // forgotten one would document itself as open.
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }

    /**
     * Two jobs, both of which exist to keep the controllers free of boilerplate
     * that would go stale.
     *
     * <p><b>The shared 401 and 403.</b> They are properties of the filter chain,
     * not of any one endpoint, so they are stated once here instead of on forty
     * methods where they could drift out of step with {@code SecurityConfig}.
     *
     * <p><b>The problem body on every error.</b> Any documented response of 400
     * or worse gets the RFC 7807 schema attached, because every error this API
     * returns has that shape. A controller therefore declares only the status
     * and why it happens, and cannot forget to say what the body looks like.
     */
    @Bean
    OpenApiCustomizer problemResponses() {
        return openApi -> {
            // Registered here rather than on the OpenAPI bean: springdoc builds
            // its own Components while scanning the controllers, and a schema
            // added up front does not survive that. The customizer runs last,
            // over the finished document.
            openApi.getComponents().addSchemas(PROBLEM_SCHEMA, problemSchema());

            openApi.getPaths().forEach((path, pathItem) ->
                    pathItem.readOperations().forEach(operation -> {
                        if (!path.startsWith("/api/auth")) {
                            operation.getResponses()
                                    .addApiResponse("401", problemResponse(
                                            "No token, or one that is expired, tampered with "
                                                    + "or issued by someone else"))
                                    .addApiResponse("403", problemResponse(
                                            "Authenticated, but the role does not permit this "
                                                    + "operation"));
                        }
                        // springdoc types every body as */*, because no handler
                        // declares `produces` -- it has no reason to, since
                        // Jackson is the only converter in play. That leaves the
                        // document vaguer than the API: an error is always
                        // problem+json and a success is always JSON.
                        //
                        // Corrected here rather than by adding `produces` to
                        // seven controllers, because this is a documentation
                        // defect and not a behavioural one. Declaring `produces`
                        // would also make content negotiation stricter at
                        // runtime, which is a real change smuggled in under a
                        // docs fix.
                        operation.getResponses().forEach((status, response) -> {
                            if (isError(status)) {
                                response.setContent(problemContent());
                            } else {
                                retypeAsJson(response);
                            }
                        });
                    }));
        };
    }

    /**
     * Re-keys a success body from the wildcard media type ({@value #ANY_MEDIA_TYPE})
     * to {@value #JSON_MEDIA_TYPE}, keeping the schema springdoc worked out. A
     * response that already names a media type, or carries no body at all, is
     * left alone.
     *
     * <p>The constants are referenced rather than written out because the
     * wildcard's second character pair closes a block comment, which is a
     * genuinely confusing way for a file to stop compiling.
     */
    private static void retypeAsJson(ApiResponse response) {
        Content content = response.getContent();
        if (content == null) {
            return;
        }
        MediaType any = content.remove(ANY_MEDIA_TYPE);
        if (any != null) {
            content.addMediaType(JSON_MEDIA_TYPE, any);
        }
    }

    private static boolean isError(String status) {
        return status.length() == 3 && status.charAt(0) >= '4' && status.charAt(0) <= '5';
    }

    private static Content problemContent() {
        return new Content().addMediaType(PROBLEM_MEDIA_TYPE,
                new MediaType().schema(new Schema<>().$ref(
                        "#/components/schemas/" + PROBLEM_SCHEMA)));
    }

    static ApiResponse problemResponse(String description) {
        return new ApiResponse().description(description).content(problemContent());
    }

    /**
     * RFC 7807, described explicitly rather than inferred from Spring's
     * {@code ProblemDetail} class, so that the {@code errors} array the
     * validation handler adds is visible too -- springdoc cannot see a property
     * that is set at runtime.
     */
    private static Schema<?> problemSchema() {
        Schema<?> violation = new ObjectSchema()
                .addProperty("field", new StringSchema().example("weightKg"))
                .addProperty("message", new StringSchema().example("must be greater than zero"));

        return new ObjectSchema()
                .description("RFC 7807 problem detail. Every error this API returns has this shape.")
                .addProperty("type", new StringSchema()
                        .description("Stable identifier for the kind of failure. Switch on this, "
                                + "never on the wording of `detail`.")
                        .example("urn:problem-type:capacity-exceeded"))
                .addProperty("title", new StringSchema().example("Position capacity exceeded"))
                .addProperty("status", new Schema<Integer>().type("integer").example(409))
                .addProperty("detail", new StringSchema()
                        .description("Human-readable explanation, including the numbers involved.")
                        .example("Position WH1-A03-B01-L01 holds 900.000 kg of its 1000.000 kg "
                                + "capacity; 500.000 kg more would exceed it"))
                .addProperty("instance", new StringSchema().example("/api/movements/inbound"))
                .addProperty("errors", new Schema<>()
                        .type("array")
                        .description("Present only on urn:problem-type:validation-failed.")
                        .items(violation))
                .required(List.of("type", "title", "status"));
    }

    private static String description() {
        return """
                Traceability and movement of stored coffee lots, modeled on real WMS concepts.

                ## The one idea this API is built on

                **Balance is not a column, it is an aggregation over history.** There is no
                `current_occupancy` and no `available_weight` anywhere in the schema. The
                `stock_movement` table is append-only -- the database refuses `UPDATE` and
                `DELETE` outright -- and every occupancy, balance and reserved weight is summed
                from it on demand. Reads cost more than a counter would; in exchange, a balance
                cannot drift from the history that produced it.

                ## Getting a token

                1. `POST /api/auth/login` with `{"username": "...", "password": "..."}`
                2. Press **Authorize** above and paste the `accessToken`.

                The development seed creates two accounts, so the role split can be tried:

                | Username | Password | Role |
                |---|---|---|
                | `warehouse.admin` | `admin123` | `ADMIN` |
                | `floor.operator` | `operator123` | `OPERATOR` |

                Both read everything. An `OPERATOR` records movements and composes shipments;
                an `ADMIN` additionally maintains the registry. Logging in as the operator and
                calling `POST /api/lots` returns `403`, which is the rule working rather than a
                fault.

                ## Errors

                Every failure is an RFC 7807 `application/problem+json` body. The `type` field
                is the stable part -- match on it, not on `detail`, whose wording may change.

                | `type` (prefixed `urn:problem-type:`) | Status | Raised when |
                |---|---|---|
                | `validation-failed` | 400 | A field or query parameter failed Bean Validation. Carries an `errors` array. |
                | `invalid-movement` | 400 | A movement that makes no sense on its face, such as a transfer to its own source. |
                | `authentication-failed` | 401 | Login was refused. Deliberately identical for a wrong password, an unknown user and a disabled account. |
                | `unauthenticated` | 401 | No usable token was presented. |
                | `forbidden` | 403 | Authenticated, but the role is not enough. |
                | `resource-not-found` | 404 | No entity with that id. |
                | `duplicate-code` | 409 | The business code is already in use. |
                | `capacity-exceeded` | 409 | Weight would push a position past `capacity_kg`, or a re-rating would drop it below what it already holds. |
                | `lot-weight-exceeded` | 409 | Cumulative inbound would exceed the lot's `net_weight_kg`. |
                | `insufficient-balance` | 409 | The source position does not hold that much of the lot. |
                | `insufficient-availability` | 409 | The stock exists but another draft shipment has already claimed it. |
                | `lot-not-movable` | 409 | The lot is `SHIPPED`, which is terminal. |
                | `out-of-order-movement` | 409 | Backdated behind a movement already recorded for the same lot at the same position. |
                | `position-not-available` | 409 | The position is inactive and cannot receive stock. |
                | `shipment-not-editable` | 409 | The shipment is `CONFIRMED` or `CANCELLED`. |
                | `empty-shipment` | 409 | A shipment with no items cannot be confirmed. |
                | `concurrent-modification` | 409 | Another request changed the same position or lot first. **Retry the request** -- nothing is wrong with it. |
                | `constraint-violation` | 409 | A database constraint refused the write. |
                """;
    }
}
