# ADR-0002: PostgreSQL now, portability kept possible

Status: accepted (revised: originally PostgreSQL, Oracle and MySQL tested together)

Decision: only PostgreSQL is built, migrated and tested for now, because testing three databases slowed every change.
The MySQL and Oracle migrations were parked in `db/migration-parked/` (they were verified at Phase 1 only) and their dialects removed (see git history).

Kept so a vendor can return: the `Dialect` interface (upsert, timestamp), schema names by configuration, and portable ANSI SQL in application code
(avoid `LIMIT`/`FETCH FIRST`, `UPDATE ... FROM`, vendor functions). PostgreSQL-only features are allowed in migrations (partial indexes, partitioning, JSONB) when they clearly pay off.

To re-add a vendor: port migrations from the parked folder and the new ones, add a Dialect, add it to CI, run the same integration tests.
