# Roadmap

One phase at a time. Every phase ends with a green `./mvnw verify` and a push to `main`.

---

## Phase 1 — Foundation ✅

Spring Boot 3.5 + Java 21 · Docker Compose (app + Postgres 16) · Flyway with a baseline migration · Actuator · springdoc/Swagger · GitHub Actions · integration tests with Testcontainers.

**Done when:** clean clone → `docker compose up --build` → Swagger at `/docs` and `/actuator/health` returning `UP`.

---

## Phase 2 — Registry ✅

CRUD for Producer, Warehouse, StoragePosition and Lot.

**Deliverables**
- JPA entities mapped against the V1 migration (with `ddl-auto: validate`, any divergence brings the application down — that is the safety net)
- `LotStatus` enum mapped as `@Enumerated(EnumType.STRING)`, matching the database `CHECK`
- DTOs as `record`: `*Request` and `*Response` kept separate
- Bean Validation on the requests
- `@RestControllerAdvice` with a standardized error body — following [RFC 7807 / ProblemDetail](https://www.rfc-editor.org/rfc/rfc7807), which Spring 6 supports natively via `ProblemDetail`
- Explicit handling for: resource not found (404), validation violation (400 with the field list), unique constraint violation (409)
- Listing with `Pageable` and filters (lot by status/crop year/producer; position by warehouse)
- Generation of the composite position code (`WH1-A03-B12-L02`) on creation
- Tests: repository (`@DataJpaTest` over Testcontainers) and controller (`@SpringBootTest` + `MockMvc`), covering the happy path and every handled error

**Out of scope for this phase:** anything to do with movement or balance.

**Done when:** all four entities are exposed, every handled error has a test, and `./mvnw verify` is green.

**Commit order**
```
docs: switch project language convention to english
refactor(db): baseline schema in english
feat(domain): producer and warehouse entities
feat(api): standardized error handling with problemdetail
feat(api): producer registry endpoints
feat(api): warehouse registry endpoints
test: repository coverage for registry constraints
feat(domain): storage position and lot entities
feat(api): storage position endpoints with composite code generation
feat(api): lot endpoints with filters and pagination
```

---

## Phase 3 — Movement ledger ✅

The heart of the project. This is the phase that separates this repo from a CRUD.

**Deliverables**
- Migration `V2__stock_movement.sql`: append-only table with type (`INBOUND`, `TRANSFER`, `OUTBOUND`), lot, source position, target position, weight, `occurred_at`, reason
- Indexes for the aggregation queries (by position, by lot)
- Operations: inbound (allocates a lot into a position), transfer (between positions), outbound
- Balance per position and per lot computed by aggregation — no caching in the first version
- Invariants enforced in the service, with dedicated domain exceptions:
  - position capacity cannot be exceeded
  - a transfer requires balance at the source
  - a `SHIPPED` lot rejects any movement
- Automatic lot status transitions driven by the movements
- **Concurrency control:** optimistic locking via `@Version` on `StoragePosition`. Document in the README why optimistic and not pessimistic, and what happens on conflict (retry vs. error to the client)
- Concurrency test: two simultaneous transfers into the same position, proving capacity does not overflow
- Statement/history endpoint for a lot

**This is the best interview material in the project.** Take care with the tests and with the comments that explain the why.

---

## Phase 4 — Shipment and blend ✅

**Deliverables**
- Migration `V3__shipment.sql`: shipment + items
- Shipment composition from multiple lots
- Blend calculation: moisture average **weighted by weight** (not a simple average — that is the point)
- Picking suggestion by FIFO on crop year
- Lots transition to `SHIPPED` when the shipment is confirmed
- Tests covering the weighted calculation with numbers that can be checked by hand

### The six decisions this phase rests on

**1. Reserved weight is derived, not flagged.** `RESERVED` is a dead enum value
until now. The naive way to give it meaning breaks rule 1: nothing physically
moves when a lot is reserved, so its ledger balance is untouched and two
shipments could each reserve the same 10,000 kg. Instead:

```
available = ledger balance − Σ(weights in items of DRAFT shipments)
```

Reserved weight is an aggregation over `shipment_item`, exactly as occupancy is
an aggregation over `stock_movement`. The same philosophy applied a second time,
which is the answer to "why no `reserved_kg` column?". A lot-level flag was
rejected as too coarse — it would make a lot un-shippable in parts, which
contradicts `shipment_item` carrying a weight at all. A `RESERVE` movement type
was rejected outright: reserved stock is still physically in the position, so it
would corrupt occupancy.

**2. The item names its source position.** An outbound needs a source and a lot
can sit in several positions. `shipment_item` carries `(lot, source_position,
weight)`, which makes it a concrete picking instruction, makes confirmation
deterministic, and lets the balance check reuse `balanceOfLotAt` unchanged. The
FIFO endpoint *proposes* those triples; the item *commits* them. Suggestion
advises, item commits.

**3. Confirmation goes through `StockMovementService.recordOutbound`.** The
shipment gets no private door into the ledger. Every Phase 3 invariant — position
locking, balance checks, `ensureMovable` — applies for free, and the concurrency
story stays a single story. Order matters: write the movements first, mark lots
`SHIPPED` after, or `ensureMovable` rejects its own shipment.

**4. A partial shipment must not mark the lot `SHIPPED`.** This is the trap in
the wording above. `SHIPPED` is terminal and immutable, so if 5,000 kg of a
12,000 kg lot ships and the lot goes `SHIPPED`, the remaining 7,000 kg is
stranded in the warehouse forever — no movement will ever be accepted for it
again. On confirmation a lot goes `SHIPPED` only if its remaining ledger balance
is zero; otherwise back to `STORED`.

**5. Classification is composed by weight, never averaged.** Moisture is numeric
and averages cleanly:

```
Σ(moisture_i × weight_i) / Σ(weight_i)
```

`screen_size`, `defect_type` and `cup_quality` are categorical — there is no
average of `HARD` and `SOFT`. Each is reported as a **weighted composition**:
every distinct value with its share of the total weight. For screen size this is
not a workaround but the trade standard itself: sieve analysis is reported as
the mass percentage retained on each screen.

Two refinements are deliberately out of scope, noted where they would land:

- The true COB standard for *defect type* averages the **defect count** weighted
  by weight and maps the result back to a type. Our schema stores the label
  (`T6`), not a count, so it is not computable without a new column.
- Trade practice treats *cup quality* as limited by the *worst* component rather
  than by an average. Surfacing that would need `cup_quality` to become a ranked
  enum.

Numeric care: a bare `BigDecimal.divide` throws on a non-terminating decimal, so
scale and `RoundingMode.HALF_UP` are always explicit. Lots with a null moisture
are excluded from **both** sides of the ratio, and the weight the average
actually covers is reported alongside it so the number cannot quietly lie.

**6. The blend is derived while DRAFT and snapshotted at confirmation.** This
looks like a rule 1 violation and is not. `updateClassification` lets a lot's
moisture be revised after the fact, so a confirmed shipment recomputed from
today's lot data would report a blend that was never shipped. A snapshot is not
a cached balance that can drift; it is a historical fact frozen at a point in
time, the same reason `occurred_at` exists. Derive while it can still change,
freeze when it becomes history.

### Lifecycle

```
DRAFT --confirm--> CONFIRMED   (terminal)
      --cancel---> CANCELLED   (terminal)
```

A confirmed shipment is never cancelled: its movements are in the ledger, and
the ledger is corrected by recording the opposite, never by deletion. Items can
only be added, changed or removed while the shipment is `DRAFT`.

### Commit order

```
docs: plan phase 4
feat(db): shipment and shipment item migration
feat(domain): shipment entity with blend calculation
test: blend weighted average with hand-checkable numbers
feat(service): shipment composition and reservation
feat(service): confirmation writing outbound movements
feat(api): shipment endpoints
feat(api): fifo picking suggestion
test: shipment lifecycle and partial-shipment status
docs: close phase 4
```

---

## Phase 5 — Finishing ✅

**Deliverables**
- OpenAPI described for real: `@Operation`, `@Schema`, request/response examples, error code descriptions. Do not ship the springdoc default
- Data seed so whoever clones the repo sees something working
- JWT authentication with Spring Security — **only here, not before.** Spring Security shows up in almost every Java job posting and its absence gets noticed
- Final README: CI badge, Swagger screenshot, revised technical decisions section
- Commit history review

### The six decisions this phase rests on

**1. The real work is not adding a login. It is adding one without
dismantling the suite that proves the other four phases.** 124 tests currently
reach MockMvc with no authentication at all. The moment the filter chain exists
every one of them takes a 401, and the tempting fix — a test configuration that
permits everything — makes the suite lie: it would keep asserting business rules
while silently asserting nothing about security.

What is done instead is the opposite split. The existing controller suites get a
class-level `@WithMockUser` with the role the endpoints under test require, and
go on proving exactly what they proved before. A new suite, and only it, proves
the security: no token is 401, the wrong role is 403, a valid login returns a
token, and that token actually opens a real endpoint. Two concerns, two places.

**2. Authorization lives in the filter chain, not on the service methods.**
`@PreAuthorize` on `StockMovementService` would scatter the rules across the
layer that is supposed to hold business invariants, and it would break
`LedgerConcurrencyTest` — which calls the service directly from a thread pool,
where the `SecurityContext` does not propagate. That test is the most valuable
thing in the repository and it should not have to know that security exists.

Request matchers in one `SecurityFilterChain` keep the whole policy readable at
a glance, which is also what makes it reviewable:

| Endpoint | Who |
|---|---|
| `POST /api/auth/login`, `/docs`, `/v3/api-docs`, `/actuator/health` | anyone |
| every `GET` under `/api` | any authenticated user |
| movements, shipments | `OPERATOR` or `ADMIN` |
| registry writes (producer, warehouse, position, lot) | `ADMIN` |

The cost, stated plainly: a rule that depended on the *contents* of a request
rather than its path would not fit this table, and would have to move to method
security. None currently does.

**3. Users are rows, and the role decides something.** A username and password
in `application.yml` would be a shortcut an interviewer notices. `V5` adds
`app_user` — username unique, BCrypt hash, role, active flag — and the role is
mapped `@Enumerated(EnumType.STRING)` against a `CHECK`, the same way
`LotStatus` already is. An OPERATOR who can move stock but cannot invent a
warehouse is an authorization model; a single `ROLE_USER` on every endpoint is
decoration.

**4. The token is signed with Spring Security's own primitives.** No third-party
JWT library: `NimbusJwtEncoder` over an `ImmutableSecret` issues it and
`NimbusJwtDecoder.withSecretKey` validates it, both already on the classpath
through `oauth2-resource-server`. HS256 with a symmetric secret rather than RS256
because there is one service issuing and one service verifying — asymmetric keys
buy the ability to let a third party verify without being able to sign, and there
is no third party here. Stateless, so no session and no CSRF token: there is no
cookie to forge a request with.

**5. A 401 does not reach `ApiExceptionHandler`, and that is the trap.**
Authentication fails inside the filter chain, before the `DispatcherServlet`, so
`@RestControllerAdvice` never sees it and Spring Security writes its own empty
body. Every error in this API has been `application/problem+json` since Phase 2,
and the two most common ones a client will actually hit would be the exceptions.

A custom `AuthenticationEntryPoint` and `AccessDeniedHandler` serialize a
`ProblemDetail` with `urn:problem-type:unauthenticated` and
`urn:problem-type:forbidden`. Same shape as the other thirteen. This is the same
class of hole as the `ConstraintViolationException` fixed before this phase: a
path that bypasses the handler everything else goes through.

**6. The seed lives outside `db/migration`.** An `R__seed.sql` there runs in
every environment, including the test one, where `AbstractIntegrationTest`
truncates it away before each case — useless in tests, and a real risk to the
repository slices that count rows. It has no business in production either.

It goes in `db/seed`, added to `spring.flyway.locations` only by the `dev`
profile, which `compose.yaml` activates. The repeatable `R__` prefix is still
right: the seed is re-applied whenever it changes rather than frozen behind a
version number. It seeds users too, or the demo cannot be logged into.

**Why SQL and not a `CommandLineRunner` calling the services.** A Java seeder
could not create state the API would refuse, which is a genuine advantage. It
loses to the fact that this seed has to write `stock_movement` rows with
believable `occurred_at` values spread over past weeks — and the ordering rule
from D6 makes that awkward to drive through the API, which validates against
*now*. SQL writes the history directly, which is what a seed is for.

### Deliberately out of scope

Named here so the omissions read as decisions rather than gaps:

- **Refresh tokens.** A short-lived access token plus a refresh token is the
  right production answer. It doubles the auth surface to demonstrate the same
  mechanism; the access token's lifetime is configurable instead.
- **`created_by` on the audit columns.** Now that requests carry an identity,
  stamping it is tempting. It touches every table and every migration for a
  field nothing reads. If it ever lands it belongs with a real audit query.
- **Rate limiting, account lockout, password rotation.** Operational concerns
  with no bearing on what this repository is meant to show.

### Commit order, as planned and as it happened

```
docs: plan phase 5
feat(db): app user migration
feat(security): jwt filter chain with roles
feat(api): login endpoint issuing the token
feat(api): problem+json for 401 and 403
test: authentication and authorization
feat(db): dev-profile seed
docs(api): openapi descriptions, examples and the problem-type catalogue
docs: final readme and decision log
docs: close phase 5
```

Two entries from the plan did not survive contact with it.

`test: the existing suites run as an authenticated user` had to travel with the
filter chain itself: its `@WithMockUser` annotations are what keep the suite
green, so separating them would have put a commit on `main` where 124 tests
fail. A green history was worth more than the tidier split.

And `docs: close phase 5` nearly became an empty commit, because the commit
before it had already marked the phase done here. Rather than leave a marker
with no content, the closing note is this section.

**Done when:** a clean clone runs `docker compose up --build`, logs in at
`/docs` with a seeded user, and every endpoint answers — with `./mvnw verify`
green and no endpoint reachable without a token that should not be.

**Verified.** 142 tests green. `docker compose up --build` on an empty volume
applies six migrations plus the seed; both accounts log in; an anonymous read
answers 401 with `WWW-Authenticate: Bearer` and a problem+json body; an operator
is refused `POST /api/lots` with 403 and may record a movement; the published
OpenAPI document describes all 37 operations, and all 130 of its error responses
reference the RFC 7807 schema.

---

## After Phase 5

The roadmap is finished. Anything below is a note for a future self rather than
a commitment, and the golden rule still applies: a half-finished addition
communicates worse than none.

Things deliberately left undone, with the reasoning recorded in `docs/DECISIONS.md`:

- refresh tokens (D7)
- `created_by` on the audit columns (D7)
- freezing the categorical blend composition, which would need JSONB or a child
  table (D5)
- defect type averaged by defect *count* rather than composed by weight, which
  needs a column the schema does not have (D5)
- cup quality as a ranked enum, so a blend could be limited by its worst
  component rather than composed (D5)
- a genuinely temporal ledger, able to accept a movement remembered out of order
  by revalidating everything after it (D6)

---

## Golden rule

**A half-finished repo communicates worse than no repo at all.** If time runs short, close the project at the end of Phase 3: adjust the README to reflect the delivered scope, remove what is still pending from the roadmap, and publish as is. Phases 1 through 3 already form a coherent, defensible product. Never leave a `TODO` or commented-out code on `main`.
