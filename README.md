# FIS Rule Engine

Batch transaction-monitoring (AML) detection for card, loan and deposit accounts (US/BSA). PostgreSQL for now (ADR-0002).

```
bank ETL ─► stg (customer, account, txn, tagged batch_id) ─[promote batch]─► mst ─[detection: Phase 2]─► aml (alerts)
```

A **batch** is one business date and succeeds or fails as a whole. On success its stg rows are deleted; on failure
fix stg and `reopen`, or `clean` and reload under a new batch id. Protocol: [`specs/data-contract/batch-protocol.md`](specs/data-contract/batch-protocol.md).

Status: Phases 0, 1 and 1b done (foundation, schemas, batch promotion). Next: rule framework (Phase 2) and rules (Phase 3).
Specs: [`specs/`](specs/README.md). Decisions: [`specs/adr/`](specs/adr).

## Run

```bash
docker compose up -d postgres            # or any PostgreSQL with schemas stg, mst, aml (ops/db/postgresql/)
export FISRE_DB_URL=jdbc:postgresql://localhost:5432/fisre FISRE_DB_USER=fisre FISRE_DB_PASSWORD=fisre
mvn -q -pl fisre-engine package -DskipTests
# migrates the schema, then runs the job: promote | clean | reopen   (none = migrate only)
FISRE_JOB=promote FISRE_BATCH_ID=2026-09-30-01 java -jar fisre-engine/target/fisre-engine-0.1.0-SNAPSHOT.jar
```

Schema names: `FISRE_SCHEMA_STG|MST|AML`. Exit code is non-zero when a batch fails.

## Test

`mvn verify` runs unit tests, the spec gate and database integration tests against the PostgreSQL in `FISRE_DB_*`
(default `localhost:5432/fisre`, user and password `fisre`). After changing migrations on a dev database, recreate the three schemas.
