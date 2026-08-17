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
