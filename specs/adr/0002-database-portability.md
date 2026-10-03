# ADR-0002: One build for PostgreSQL, Oracle and MySQL

Status: accepted

Decision: pick the vendor and schema names by configuration (`FISRE_DB_VENDOR`, `FISRE_DB_URL`, `FISRE_SCHEMA_*`).
- DDL lives in `db/migration/<vendor>/` (Flyway). Seed data shared in `db/migration/common/`.
- Application SQL is portable ANSI. The only vendor-specific SQL is behind `Dialect` (upsert, timestamp).
- Avoid: `FETCH FIRST`/`LIMIT`, `UPDATE ... FROM`, self-referencing UPDATE subqueries (MySQL 1093), vendor functions.
- Logical schemas stg, mst, aml map to a schema (PostgreSQL), database (MySQL) or user (Oracle). DBAs create them; see `ops/db/`.

Testing: the same integration tests run against each vendor in CI. MySQL does not enforce the inline foreign keys; validation rules enforce referential integrity on all vendors.
