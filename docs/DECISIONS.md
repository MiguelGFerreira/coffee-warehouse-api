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


---

## D4 — The lot is the second serialization point, and the lock must not leak

**Date:** 2026-09-09 · **Status:** accepted

`net_weight_kg` is what the producer delivered and it never changes, so it is a
ceiling on everything the ledger can record arriving for that lot. Phase 3 did
not enforce it: a 100 kg lot could be received 1,000,000 kg at a time.

**Why the ceiling counts receipts, not balance.** A lot that arrived at 12,000 kg
and shipped out entirely has a balance of zero, but that coffee is gone — it does
not become receivable again. The cap is on cumulative `INBOUND`, which also lets
a lot be put away in parts across several positions and still add up to exactly
its net weight.

**Why the lot needs a forced version increment too.** Exactly the D2 argument,
one aggregate over. Only the *first* inbound dirties the lot row, through
`markStored()`; every one after that leaves it untouched, so two concurrent
inbounds into two *different* positions would both read the same total received,
both find room under the net weight, and both insert. An inbound now loads the
lot with `OPTIMISTIC_FORCE_INCREMENT` as well. A transfer and an outbound do
not: neither can change what has been received, and forcing the increment there
would turn two unrelated transfers of the same lot into a 409 neither earned.

### The bug this uncovered

Writing the test that proves the lot defends its own ceiling exposed a defect in
D2's implementation. `findByIdAndLock` on the position combined
`@Lock(OPTIMISTIC_FORCE_INCREMENT)` with `@EntityGraph("warehouse")`, and
**Hibernate cascades the lock mode to whatever the query join-fetches.** Every
movement was therefore forcing the version of the *warehouse* up as well:

```
update warehouse set version=? where id=? and version=?
```

The serialization point was not the position. It was the entire warehouse. Two
operators putting stock into two completely unrelated bins of the same warehouse
would collide, and one would get a `409` it had done nothing to earn — the exact
cost D2 rejected pessimistic locking to avoid, paid anyway and at a far coarser
grain. The concurrency test never caught it because a warehouse-wide lock is
strictly stronger than a position-wide one: it made the assertion pass for the
wrong reason.

Both locking finders now fetch nothing. Neither the movement path nor the
inbound path reads the warehouse or the producer, so both associations stay
lazy and each lock reaches exactly one row.

### What keeps this honest

Each race isolates one aggregate, which takes deliberate setup because two
force-incremented aggregates in one operation cover for each other:

- the **position** race runs two *different lots* into *one position*, so no two
  lot versions can collide and only the position can be serializing;
- the **lot** race runs *one lot* into *two different positions*, so no two
  position versions can collide and only the lot can be.

Both were verified to fail when their own lock mode is removed, and to fail
*only* there. That property is the whole value of the test: the original
warehouse-cascade bug survived precisely because nothing checked it.


---

## D5 — Reservation is an aggregation, and SHIPPED means empty

**Date:** 2026-09-09 · **Status:** accepted

Three decisions from Phase 4 that a reader is likely to push on.

### Reserved weight is never a column

`RESERVED` was a dead value in `LotStatus` until this phase. The tempting way to
give it meaning is a `reserved_kg` on the lot, and it is wrong for the same
reason `current_occupancy` is: putting a lot on a draft shipment moves nothing,
so its ledger balance is untouched, and two shipments checking the balance alone
would each see the full 10,000 kg and each claim it.

What is committed is summed from the items of DRAFT shipments, so:

```
available = balance at the position − weight claimed by other draft shipments
```

Both halves are aggregations. The second one excludes the shipment being edited,
because a line's *new total* is what has to fit — counting the shipment's own
current claim would charge it twice for weight it is about to replace.

Only DRAFT counts. A confirmed shipment has already taken its weight out through
the ledger, so charging it again would hide stock that is genuinely gone; a
cancelled one never took anything.

**The status is still a label, and it is recomputed rather than toggled.** It
answers "is any of this lot spoken for?", never "how much". A lot on two drafts,
released by one, is still held by the other — so every transition asks the
aggregation instead of flipping a bit. Cancelling releases before it changes
status, since items stop counting as reservations the moment a shipment leaves
DRAFT.

Composition takes the same lock an inbound does (D4): availability is summed
from rows that are only ever inserted, and `markReserved()` dirties the lot only
on the first claim.

### A partially dispatched lot must not be SHIPPED

The roadmap says "lots transition to `SHIPPED` when the shipment is confirmed",
and taken literally that is a bug. `SHIPPED` is terminal and `ensureMovable`
refuses every later movement, so a lot that sent 5,000 of its 12,000 kg would
have the remaining 7,000 stranded in the warehouse permanently — no transfer, no
second shipment, no correction.

A lot goes `SHIPPED` only when its remaining balance is zero. Otherwise it is
ordinary stored stock again, or stays `RESERVED` if another draft still holds
it: this dispatch settled its own claim, not everyone else's.

`ShipmentControllerTest.partialDispatchDoesNotStrandTheRemainder` fails with
"expected STORED but was SHIPPED" against the literal implementation, which is
the only thing that makes it worth having.

### The blend is frozen, the composition is not

A lot's moisture is revisable — `updateClassification` exists for exactly that —
so a confirmed shipment recomputed from today's lot data would report a blend
that was never shipped. The weight, the moisture average and its basis are
copied onto the row at confirmation and never recomputed. That is not the
cached-balance mistake rule 1 exists to prevent: it is a historical fact frozen
at a point in time, the same reason a movement records `occurred_at` separately
from when it was written.

**The categorical composition stays derived, and that asymmetry is deliberate.**
The moisture average is a figure recorded at dispatch, the kind a contract
quotes; the screen, defect and cup shares are a descriptive view of how the lots
are graded, and a re-grade is new information about the same coffee rather than
a change to what was sent. The honest cost: re-grading a lot after dispatch does
move the reported composition of a confirmed shipment while its moisture stays
put. Freezing the composition too would mean a JSONB column or a child table for
the shares — more schema than the phase needed, and the reason the item weights
are immutable after confirmation is so that the only thing that can move is the
label.

### Why classification is composed rather than averaged

There is no average of `HARD` and `SOFT`. Each categorical attribute is reported
as the share of total weight behind every distinct value, which for screen size
is not a workaround but the trade standard: a sieve analysis reports the mass
percentage retained on each screen.

Shares divide by the *total* weight, not the graded weight, so a shortfall below
100% is visible as coffee nobody graded rather than hidden behind a denominator
that quietly shrank. Moisture is the opposite — it divides by the weight that
actually has a reading, because averaging over the total would count an ungraded
lot as 0% and drag the result down, which is a wrong number rather than an
incomplete one. `moistureBasisKg` reports the difference.

Two refinements were left out on purpose. The true COB standard for defect type
averages the **defect count** weighted by weight and maps back to a type, which
needs a count column the schema does not have. And trade practice treats cup
quality as limited by the *worst* component rather than by an average, which
would need `cup_quality` to become a ranked enum.
