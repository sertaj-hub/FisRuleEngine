# FIS Rule Engine

Batch transaction-monitoring (AML) detection for card, loan and deposit accounts (US/BSA). PostgreSQL for now (ADR-0002).

```
bank ETL ─► stg (customer, account, txn, tagged batch_id) ─[promote batch]─► mst ─[detect: rules]─► aml (alerts)
```

A **batch** is one business date and succeeds or fails as a whole. On success its stg rows are deleted; on failure
fix stg and `reopen`, or `clean` and reload under a new batch id. Protocol: [`specs/data-contract/batch-protocol.md`](specs/data-contract/batch-protocol.md).

Status: Phases 0 to 3 done (foundation, batch promotion, rule framework, seven starter rules). Next: performance at 10M transactions a day (Phase 4).
Specs: [`specs/`](specs/README.md). Decisions: [`specs/adr/`](specs/adr).

## Run

```bash
docker compose up -d postgres            # or any PostgreSQL with schemas stg, mst, aml (ops/db/postgresql/)
export FISRE_DB_URL=jdbc:postgresql://localhost:5432/fisre FISRE_DB_USER=fisre FISRE_DB_PASSWORD=fisre
mvn -q -pl fisre-engine package -DskipTests
# every run migrates the schema first, then runs one job: promote | clean | reopen | load-rules | detect   (none = migrate only)
JAR=fisre-engine/target/fisre-engine-0.1.0-SNAPSHOT.jar
FISRE_JOB=promote FISRE_BATCH_ID=2026-09-30-01 java -jar $JAR      # stg -> mst for one batch
FISRE_JOB=load-rules FISRE_RULES_DIR=specs/rules java -jar $JAR    # rule specs -> aml.rule (versioned)
FISRE_JOB=detect FISRE_BUSINESS_DATE=2026-09-30 java -jar $JAR     # active rules -> aml.alert
```

Schema names: `FISRE_SCHEMA_STG|MST|AML`. Exit code is non-zero when a batch fails or any rule fails.

## Rules

A rule is a YAML file in [`specs/rules/`](specs/rules) that configures a generic template (no code, no severity). Each file carries its own test
scenarios, which the build runs against the database. Reference: [`specs/data-contract/rule-config.md`](specs/data-contract/rule-config.md), ADR-0004.

| Rule | Template |
|---|---|
| LARGE_CASH_DAILY, STRUCTURING_CASH_DEPOSITS, CARD_CASH_ADVANCE_VELOCITY | AGGREGATE |
| RAPID_MOVEMENT_OF_FUNDS | FLOW_THROUGH |
| CREDIT_BALANCE_REFUND, LOAN_EARLY_PAYOFF | SEQUENCE |
| DORMANT_REACTIVATION | DORMANT_REACTIVATION |

## Test

`mvn verify` runs unit tests, the spec gate and database integration tests against the PostgreSQL in `FISRE_DB_*`
(default `localhost:5432/fisre`, user and password `fisre`). After changing migrations on a dev database, recreate the three schemas.
