# FIS Rule Engine: working agreements

Batch AML detection platform (US BSA; cards, loans, deposits). Alerts will feed a separate case-management product (not in this repo).

- Build: `mvn verify` (Java 21, Maven). DB for tests = `FISRE_DB_VENDOR` / `FISRE_DB_URL` (default local PostgreSQL, see README).
- Spec-driven: change `specs/` first, then add a `@Req("REQ-...")` test, then code. `SpecCoverageTest` enforces it.
- Portable SQL only (ADR-0002): vendor-specific SQL goes in `Dialect` or `db/migration/<vendor>/`. No `LIMIT`/`FETCH FIRST`, `UPDATE ... FROM`, or self-referencing UPDATE subqueries.
- Set-based SQL, never row-by-row in Java (ADR-0001).
- Every migration change is made in all three vendor folders.
- Out of scope: sanctions/watchlist, KYC scoring, real-time detection.
