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

## Phase 4 — Shipment and blend

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
