# Roadmap

One phase at a time. Every phase ends with a green `./mvnw verify` and a push to `main`.

---

## Phase 1 — Foundation ✅

Spring Boot 3.5 + Java 21 · Docker Compose (app + Postgres 16) · Flyway with a baseline migration · Actuator · springdoc/Swagger · GitHub Actions · integration tests with Testcontainers.

**Done when:** clean clone → `docker compose up --build` → Swagger at `/docs` and `/actuator/health` returning `UP`.

---

## Phase 2 — Registry

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

## Phase 3 — Movement ledger

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

## Phase 4 — Shipment and blend

**Deliverables**
- Migration `V3__shipment.sql`: shipment + items
- Shipment composition from multiple lots
- Blend calculation: moisture average **weighted by weight** (not a simple average — that is the point)
- Picking suggestion by FIFO on crop year
- Lots transition to `SHIPPED` when the shipment is confirmed
- Tests covering the weighted calculation with numbers that can be checked by hand

---

## Phase 5 — Finishing

**Deliverables**
- OpenAPI described for real: `@Operation`, `@Schema`, request/response examples, error code descriptions. Do not ship the springdoc default
- Data seed (migration `R__seed.sql` or a `dev` profile) so whoever clones the repo sees something working
- JWT authentication with Spring Security — **only here, not before.** Spring Security shows up in almost every Java job posting and its absence gets noticed
- Final README: CI badge, Swagger screenshot, revised technical decisions section
- Commit history review

---

## Golden rule

**A half-finished repo communicates worse than no repo at all.** If time runs short, close the project at the end of Phase 3: adjust the README to reflect the delivered scope, remove what is still pending from the roadmap, and publish as is. Phases 1 through 3 already form a coherent, defensible product. Never leave a `TODO` or commented-out code on `main`.
