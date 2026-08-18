# CLAUDE.md

Project context for Claude Code sessions. Read this before proposing any change.

---

## What this project is

REST API for traceability and movement of stored coffee lots, modeled on real WMS concepts.

This is a **portfolio project** by a developer targeting a mid-level Java role. That sets the priority: the code has to be defensible in a technical interview. Clarity, justified decisions and tests matter more than feature count.

---

## Non-negotiable rules

These were decided deliberately. **Do not suggest the opposite unless the author asks.**

1. **Balance is not a column, it is a ledger aggregation.** There is no `storage_position.current_occupancy` and no `lot.available_weight`. The `stock_movement` table is append-only: never `UPDATE`, never `DELETE`. All occupancy and all balances are derived from it.
2. **`ddl-auto: validate`, never `update` or `create`.** The schema belongs to Flyway. Every structural change goes in as a new versioned migration — never edit a migration that has already been committed.
3. **Testcontainers with a real Postgres. Never H2.** Constraints, `NUMERIC` and `CHECK` have to be exercised in the tests.
4. **Layered with a rich domain. This is not hexagonal.** No ports/adapters, no ceremonial `application/domain/infrastructure`. Business rules live inside the entity when they belong to it; the service orchestrates; the controller is thin.
5. **`open-in-view: false`.** Do not revert.
6. **No Lombok.** Java 21 has `record` for DTOs. JPA entities write the methods they need. This is a conscious choice: it avoids magic an interviewer might question.
7. **No proprietary information.** The domain is modeled from public knowledge of the coffee industry (COB classification, generic warehouse addressing). Never introduce a company name, a client, a real warehouse layout, or a business rule specific to a commercial system (Protheus, Agrosync). If a suggestion looks like it came from a proprietary system, ask first.

---

## Code conventions

- **Java 21.** Use `record`, pattern matching, text blocks and `sealed` where they fit naturally. Do not force it.
- **Language: English, everywhere.** Domain terms, schema, code, comments, test names and commit messages. The project was internationalized before Phase 2; see `docs/DECISIONS.md` for the vocabulary table and the one-off exception it required.
- **DTOs are `record`**, always separate from the entity. A JPA entity is never serialized straight into a response.
- **Validation** with Bean Validation on the inbound DTOs.
- **Money and weight** are always `BigDecimal`. Never `double`.
- **Dates** with `LocalDate` / `OffsetDateTime`. UTC in the database.
- **Constructor injection**, no field `@Autowired`.
- **Tests:** JUnit 5 + AssertJ. Test names in English with `@DisplayName` describing behavior, not implementation.

---

## Package structure

```
tech.migueldev.coffeewarehouse
├── domain/          JPA entities + the rules that belong to them + enums
├── repository/      Spring Data interfaces
├── service/         orchestration and invariants that cross entities
├── api/
│   ├── controller/
│   ├── dto/         request/response records
│   └── exception/   @RestControllerAdvice + error types
└── config/
```

---

## Commands

```bash
docker compose up --build          # app + database
docker compose up -d db            # database only (app runs in the IDE)
docker compose down -v             # tear down and drop the volume
./mvnw verify                      # build + tests (needs Docker)
./mvnw spring-boot:run             # run locally against the compose database

docker exec -it coffee-db psql -U coffee -d coffee_warehouse
```

Swagger: http://localhost:8080/docs · Health: http://localhost:8080/actuator/health

---

## Domain model

**Entities:** Producer, Warehouse, StoragePosition, Lot, StockMovement, Shipment, ShipmentItem

**Addressing:** warehouse → aisle → bay → level, composite code such as `WH1-A03-B12-L02`

**Lot lifecycle:** `AWAITING_ALLOCATION → STORED → RESERVED → SHIPPED` (terminal and immutable)

**Invariants:**
- the sum allocated to a position never exceeds `capacity_kg`
- a transfer requires sufficient balance at the source
- a `SHIPPED` lot accepts no movement
- picking suggestion follows FIFO by crop year
- shipment blend: moisture and classification averaged **weighted by weight**

**Concurrency:** two simultaneous transfers into the same position could blow past capacity. Solved with optimistic locking — the `version` column has existed on every table since V1.

---

## Current state

**Phase 1 done:** Spring Boot 3.5 + Java 21, Docker Compose with Postgres 16, baseline migration (producer, warehouse, storage_position, lot), Actuator, springdoc, CI on GitHub Actions, integration tests with Testcontainers.

**Phase 2 in progress.** Producer and Warehouse are complete and set the pattern to follow: entity extending `AuditableEntity` with the business code as identity, repository, service, `record` DTOs split into create/update/response, thin controller, RFC 7807 errors through `ApiExceptionHandler`, controller test plus a repository slice test for the database constraints.

**Still open in Phase 2:** StoragePosition (including composite code generation) and Lot (including the `LotStatus` enum and the filtered listing). See `docs/ROADMAP.md`.

---

## How to work with me

- **Direct feedback, no empty praise.** If an idea of mine is bad, say so and explain why.
- **One phase at a time.** Do not write code for future phases.
- **Explain the trade-off** whenever there is more than one reasonable path — this project exists so I can defend the decisions in an interview.
- **Small, semantic commits** (`feat:`, `fix:`, `test:`, `docs:`, `chore:`, `build:`, `ci:`), in English.
- Run `./mvnw verify` before considering any task done.
