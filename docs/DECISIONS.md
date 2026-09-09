# Decision log

Short records of decisions that are not obvious from the code, and that a reader
(or an interviewer) is likely to ask about.

---

## D1 — The project language is English

**Date:** 2026-08-17 · **Status:** accepted

The domain was originally modeled in Portuguese: `produtor`, `armazem`, `posicao`,
`lote`, and the columns to match. Everything was translated to English before
Phase 2 began.

**Why then.** Not a single line of Java existed yet. There was one migration, no
deployed environment, and the development workflow already treats the database as
disposable (`docker compose down -v`). Every day of delay would have made the
change more expensive; there was never going to be a cheaper moment.

**Why at all.** The repository is a portfolio artifact read by people who may not
speak Portuguese, and a codebase that mixes an English framework with a Portuguese
domain forces a translation decision at every new identifier.

### Vocabulary

| Portuguese | English | Note |
|---|---|---|
| `produtor` | `producer` | |
| `armazem` | `warehouse` | |
| `posicao` | `storage_position` | `position` is a reserved function name in SQL; avoids quoting |
| `lote` | `lot` | the standard term for coffee lots in industry English |
| `movimentacao` | `stock_movement` | |
| `embarque` / `item_embarque` | `shipment` / `shipment_item` | |
| `rua` / `coluna` / `nivel` | `aisle` / `bay` / `level` | `column` is reserved in SQL; aisle/bay/level is standard WMS vocabulary |
| `codigo` / `nome` | `code` / `name` | |
| `municipio` / `uf` | `city` / `state` | |
| `criado_em` / `atualizado_em` | `created_at` / `updated_at` | |
| `capacidade_kg` / `ativa` | `capacity_kg` / `active` | |
| `peso_liquido_kg` / `sacas` | `net_weight_kg` / `bags` | |
| `umidade_percentual` | `moisture_percent` | |
| `peneira` / `tipo` / `bebida` | `screen_size` / `defect_type` / `cup_quality` | COB classification, public terminology |
| `safra` / `data_entrada` | `crop_year` / `received_on` | |
| `AGUARDANDO_ALOCACAO` | `AWAITING_ALLOCATION` | |
| `ARMAZENADO` / `RESERVADO` / `EMBARCADO` | `STORED` / `RESERVED` / `SHIPPED` | |
| `AR1-R03-C12-N2` | `WH1-A03-B12-L02` | position code format |

### The exception this required

Rule 2 in `CLAUDE.md` says a committed migration is never edited. The baseline
migration `V1` was rewritten in place anyway, rather than being followed by a
`V2` renaming the four tables it had just created.

That rule exists to protect databases holding real data and a shared history.
Neither existed: the only consumer of `V1` was a Testcontainers instance created
and destroyed per test run. The alternative would have put a rename-everything
migration in the permanent history of a repository whose purpose is to be read.

**This exception is closed.** From `V2` onward, migrations are immutable.

**Consequence for anyone with an older local volume.** Flyway records the
checksum of every migration it applies, so a database created before the rewrite
refuses to start against the new `V1`:

```
Migration checksum mismatch for migration version 1
```

The fix is `docker compose down -v` and a fresh start. Testcontainers builds a
new database per run and CI clones from scratch, so neither ever saw this --
only a developer machine that had run the project before the rewrite does.


---

## D2 — Position capacity is guarded by a forced version increment

**Date:** 2026-09-07 · **Status:** accepted

Two movements into the same position, at the same time, must not be able to push
it past `capacity_kg`.

**Why the obvious answer does not work.** Every table has carried a `version`
column since `V1`, so it is tempting to say the invariant is already covered by
optimistic locking. It is not. Recording a movement reads
`SUM(weight_kg)` over the ledger and then INSERTs a row; it never updates
`storage_position`. With nothing writing to that row, its version never moves
and there is no conflict to detect. Under `READ COMMITTED` neither transaction
sees the other's uncommitted movement either. Both find room, both insert, and
the position ends up over capacity with no error raised anywhere.

**What is done instead.** Positions are loaded with
`LockModeType.OPTIMISTIC_FORCE_INCREMENT`. Hibernate then issues a version
UPDATE on the position at commit even though no field changed. Two overlapping
transactions both read version *N*; the first commits *N+1*, the second finds
its UPDATE matching zero rows and fails with
`OptimisticLockingFailureException`, which the API returns as `409` with
`urn:problem-type:concurrent-modification`.

That is the aggregate-root argument stated in code: the position owns an
invariant that spans its movements, so the position's version is where those
movements serialize.

**Why not pessimistic.** `SELECT ... FOR UPDATE` would also be correct and is
simpler to explain. It pays a row lock on every movement to serialize a
collision that is rare in a warehouse -- two operators filling the same bin in
the same instant is the exception. Optimistic pays nothing until the collision
happens and costs a retry when it does.

**Why not SERIALIZABLE.** Postgres would detect the read-write conflict and
abort one transaction, which is arguably the most honest fit for a ledger. It
was rejected for this project because it pushes retry handling into every caller
for a guarantee the forced increment already gives on the one row that matters.

**The test that keeps this honest.** `LedgerConcurrencyTest` forces the
interleaving with a barrier rather than hoping two threads collide. Two earlier
versions of that test passed against a deliberately broken implementation --
first because the threads never overlapped, then because `markStored()` dirtied
the *lot* and the lot's own version was quietly doing the serializing. The test
as it stands fails when the lock mode is removed, which is the only property
that makes it worth having.


---

## D3 — The capacity invariant is guarded from both sides

**Date:** 2026-09-09 · **Status:** accepted

The invariant is *stored weight never exceeds capacity*. It has two sides, and
Phase 3 originally guarded only one.

`ensureFits` refuses weight arriving into a position that has no room for it.
Nothing, however, refused `PUT /api/storage-positions/{id}` re-rating a position
holding 12,000 kg down to 100 kg. The registry endpoint could do what no
movement was allowed to do, and the occupancy endpoint would then report
`availableKg: -11900`.

**What was done.** `changeCapacity` now takes the current occupancy alongside
the new rating and refuses to drop below it, in the same shape as `ensureFits`
and raising the same `CapacityExceededException`. One invariant, one problem
type: a client that already handles `urn:problem-type:capacity-exceeded` needs
no new branch, and the API does not grow a second name for the same rule.

**Why the service passes the occupancy in.** For the same reason `ensureFits`
takes it as a parameter: it is an aggregation over the ledger, and an entity
that reached for a repository to answer a question about itself would be a
worse trade than the argument.

**Why this path needs no forced version increment.** A movement gets
`OPTIMISTIC_FORCE_INCREMENT` because it never writes to the position row (D2).
A re-rating does write to it, so the ordinary `@Version` already serializes it
against an inbound racing to fill the position it is shrinking; the loser gets
the same `409`.

**What is deliberately still allowed.** Re-rating down to exactly the current
occupancy succeeds — a full position can be re-rated to full. The rule is
"cannot fall below what is stored", not "cannot shrink".
