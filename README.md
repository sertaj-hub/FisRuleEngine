# FIS Rule Engine

Batch transaction-monitoring (AML) detection for card, loan and deposit accounts (US/BSA).
Runs on PostgreSQL, Oracle or MySQL, chosen by configuration.

```
bank ETL ─► stg (customer, account, txn) ─[promote batch]─► mst ─[detection batch: Phase 2]─► aml (alerts)
```

Status: **Phase 0-1 done** (foundation, schemas, validation and promotion). Next: rule framework and first rules.
Specs: [`specs/`](specs/README.md). Decisions: [`specs/adr/`](specs/adr).

## Run

```bash
# 1. a database with schemas stg, mst, aml (see ops/db/<vendor>/init.sql, or docker compose up postgres)
# 2. configure and run
export FISRE_DB_VENDOR=postgresql            # postgresql | mysql | oracle
export FISRE_DB_URL=jdbc:postgresql://localhost:5432/fisre
export FISRE_DB_USER=fisre FISRE_DB_PASSWORD=fisre
mvn -q -pl fisre-engine package -DskipTests
FISRE_JOB=promote java -jar fisre-engine/target/fisre-engine-0.1.0-SNAPSHOT.jar   # migrates, then promotes
```

JDBC URLs: MySQL `jdbc:mysql://host:3306/aml` · Oracle `jdbc:oracle:thin:@//host:1521/SERVICE`.
Schema names: `FISRE_SCHEMA_STG|MST|AML`.

## Test

`mvn verify` runs unit tests, the spec gate and database integration tests against the configured database.
CI runs the same tests on all three vendors.

| Vendor | Verified |
|---|---|
| PostgreSQL 16 | locally and CI |
| MySQL 8 | locally and CI |
| Oracle 23 | CI only (first run pending; not yet run anywhere) |
