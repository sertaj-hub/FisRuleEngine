# Specs: the source of truth

We build spec-first. A change starts here, then tests, then code.

| Folder | Holds |
|---|---|
| `requirements/` | Numbered requirements (`REQ-<AREA>-<NNN>`) with a status. |
| `data-contract/` | The tables, columns, domains and validation rules the bank's ETL and the engine agree on. |
| `adr/` | Architecture decision records. |
| `rules/` | One spec per detection rule (from Phase 2). |

## The gate

`SpecCoverageTest` (runs in every build) fails when:
1. a requirement marked `Implemented` has no test annotated `@Req("REQ-...")`, or
2. a test references a requirement id that does not exist.

Lifecycle: add a requirement as `Planned` → write the failing test → implement → flip to `Implemented`.
