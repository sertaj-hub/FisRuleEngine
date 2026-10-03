# FIS Rule Engine: working agreements

Batch AML detection platform (US BSA; cards, loans, deposits). Alerts will feed a separate case-management product (not in this repo).

- Build: `mvn verify` (Java 21, Maven). Tests need a local PostgreSQL with schemas stg, mst, aml (README). PostgreSQL only for now (ADR-0002).
- Spec-driven: change `specs/` first, then add a `@Req("REQ-...")` test, then code. `SpecCoverageTest` enforces it.
- Keep application SQL portable ANSI where it costs nothing; vendor SQL goes in `Dialect`. PostgreSQL-only features are fine in migrations.
- Set-based SQL, never row-by-row in Java (ADR-0001).
- Scale (ADR-0005): `txn` is partitioned by posting day and built offline then swapped in; staging is one unlogged partition per batch; never UPDATE staged rows; never put correlated EXISTS or OR-of-subselects in a per-row CASE (use joins or separate queries).
- Batch model: a batch (one business date) succeeds or fails as a whole (ADR-0003, `specs/requirements/batch.md`).
- Rules are configured templates, not code (ADR-0004): a new rule is a YAML file in `specs/rules/` with test scenarios; `RuleSpecIT` runs them. No per-rule severity.
- Hardening (ADR-0007): one mutating job at a time (`JobLock`), statement and rule timeouts, handed-off alerts are immutable, schema names are validated, config values are always bound parameters, the app refuses the default DB password. Run jars locally with `FISRE_ALLOW_DEFAULT_CREDENTIALS=true`.
- Alert delivery (ADR-0008): alerts go to case management as one reconciled delivery per business date, never one by one; a published delivery is frozen, corrections are new revisions or `WITHDRAWN` events; the consumer contract is `aml.v_alert_*` plus `confirm_delivery`/`reject_alerts` (`specs/data-contract/alert-export.md`).
- Out of scope: sanctions/watchlist, KYC scoring, real-time detection.
