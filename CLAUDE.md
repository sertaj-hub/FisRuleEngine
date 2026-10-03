# FIS Rule Engine: working agreements

Batch AML detection platform (US BSA; cards, loans, deposits). Alerts will feed a separate case-management product (not in this repo).

- Build: `mvn verify` (Java 21, Maven). Tests need a local PostgreSQL with schemas stg, mst, aml (README). PostgreSQL only for now (ADR-0002).
- Spec-driven: change `specs/` first, then add a `@Req("REQ-...")` test, then code. `SpecCoverageTest` enforces it.
- Keep application SQL portable ANSI where it costs nothing; vendor SQL goes in `Dialect`. PostgreSQL-only features are fine in migrations.
- Set-based SQL, never row-by-row in Java (ADR-0001).
- Batch model: a batch (one business date) succeeds or fails as a whole (ADR-0003, `specs/requirements/batch.md`).
- Out of scope: sanctions/watchlist, KYC scoring, real-time detection.
