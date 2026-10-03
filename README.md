# FIS Rule Engine

Batch transaction-monitoring (AML) detection for card, loan and deposit accounts (US/BSA). PostgreSQL for now (ADR-0002).

```
bank ETL ─► stg (customer, account, txn, tagged batch_id) ─[promote batch]─► mst ─[detect: rules]─► aml (alerts)
```

A **batch** is one business date and succeeds or fails as a whole. The 1 am batch for business date D carries the transactions posted on **D-1** (`FISRE_POSTING_OFFSET_DAYS`, default 1). On success its staging partitions are dropped; on failure
fix stg and `reopen`, or `clean` and reload under a new batch id. The ETL registers a batch by inserting into `aml.load_batch`, which creates its staging partitions `<table>_b<batch_seq>`; load straight into those for speed. Protocol: [`specs/data-contract/batch-protocol.md`](specs/data-contract/batch-protocol.md).

Status: Phases 0 to 5 done (foundation, batch promotion, rule framework, nine rules, partitioned scale design, nightly run, database handoff to case management). Designed for 10M transactions a day and 13 months of history ([ADR-0005](specs/adr/0005-scale-design.md)).
Specs: [`specs/`](specs/README.md). Decisions: [`specs/adr/`](specs/adr).

## Run

```bash
docker compose up -d postgres            # or any PostgreSQL with schemas stg, mst, aml (ops/db/postgresql/)
export FISRE_DB_URL=jdbc:postgresql://localhost:5432/fisre FISRE_DB_USER=fisre FISRE_DB_PASSWORD=fisre
mvn -q -pl fisre-engine package -DskipTests
# every run migrates the schema first, then runs one job (none = migrate only):
#   nightly | promote | promote-loaded | clean | reopen | load-rules | detect | retain | generate
JAR=fisre-engine/target/fisre-engine-0.1.0-SNAPSHOT.jar
FISRE_JOB=nightly FISRE_BATCH_ID=2026-10-01-01 FISRE_BUSINESS_DATE=2026-10-01 java -jar $JAR   # the one nightly command: promote, detect, retain
FISRE_JOB=promote FISRE_BATCH_ID=2026-10-01-01 java -jar $JAR      # stg -> mst for one batch
FISRE_JOB=load-rules FISRE_RULES_DIR=specs/rules java -jar $JAR    # rule specs -> aml.rule (versioned)
FISRE_JOB=detect FISRE_BUSINESS_DATE=2026-10-01 java -jar $JAR     # active rules -> aml.alert
FISRE_JOB=promote-loaded java -jar $JAR                            # history: every LOADED batch, earliest first then in parallel
FISRE_JOB=retain FISRE_BUSINESS_DATE=2026-10-01 java -jar $JAR     # drop partitions older than 13 months
FISRE_JOB=generate FISRE_BATCH_ID=G1 FISRE_BUSINESS_DATE=2026-10-01 FISRE_BENCH_TXNS=1000000 FISRE_BENCH_ACCOUNTS=100000 java -jar $JAR   # synthetic batch
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
| DEBIT_SPIKE_VS_BASELINE | BASELINE_DEVIATION |
| NEW_COUNTERPARTY_COUNTRY | NEW_ATTRIBUTE |

## Case management

Case management reads alerts from the database: view `aml.v_alert_export` (one self-contained JSON payload per unsent alert) and function `aml.ack_alerts(ids)` to confirm pickup. Contract: [`specs/data-contract/alert-export.md`](specs/data-contract/alert-export.md), ADR-0006. Day-to-day operation and failure handling: [`ops/RUNBOOK.md`](ops/RUNBOOK.md).

## Test

`mvn verify` runs unit tests, the spec gate and database integration tests against the PostgreSQL in `FISRE_DB_*`
(default `localhost:5432/fisre`, user and password `fisre`). After changing migrations on a dev database, recreate the three schemas.
