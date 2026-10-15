# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

FIS Rule Engine: batch AML transaction-monitoring detection (US BSA; cards, loans, deposits). Alerts feed a separate case-management product (not in this repo) through a database contract. Out of scope: sanctions/watchlist, KYC scoring, real-time detection.

## Commands

Everything needs a local PostgreSQL 16 with schemas `stg`, `mst`, `aml` (`docker compose up -d postgres` creates them). Environment variables do not persist between Bash calls in this sandbox: export them in the same command.

```bash
export FISRE_DB_URL=jdbc:postgresql://localhost:5432/fisre FISRE_DB_USER=fisre FISRE_DB_PASSWORD=fisre FISRE_ALLOW_DEFAULT_CREDENTIALS=true
mvn -q -B -o verify                                   # everything: unit tests (surefire) + *IT integration tests (failsafe) + spec gate. Use -o when offline; the first build with new dependencies needs network
mvn -q -B -o verify -Dtest=RuleWorkflowIT             # one test class (also works for *IT classes); -Dtest='Foo#method' for one method
mvn -q -B -o -DskipTests package                      # jar: fisre-engine/target/fisre-engine-0.1.0-SNAPSHOT.jar
cd fisre-ml && pip install -e '.[test]' && python -m pytest -q     # ML module (about 90 s; builds synthetic data)
```

Surefire XML reports under `fisre-engine/target/` can be stale from an earlier run: trust the Maven exit code. `service postgresql start` if the sandbox database stopped. Do not `pkill -f` a pattern that appears in your own command line (it kills the shell).

Running the app: one process, one job, chosen by `FISRE_JOB` (list in `application.yml`; README "Run"). `nightly` runs promote, detect and retain; it does not call the Python scorer, so with an ACTIVE ML rule `fisre-ml score` must run between promote and detect. `FISRE_JOB=serve` is the only job that starts a web server (rule UI, listens on 127.0.0.1:8080).

## Architecture (the parts that span files)

**Data flow.** ETL registers a batch in `aml.load_batch` and loads `stg` (one unlogged list partition per batch, created by a trigger) → `promote` validates per entity (rejects in `aml.load_reject`), moves to `mst` (`txn` is range-partitioned by posting day, built offline and swapped with DETACH/ATTACH) → `detect` runs the ACTIVE rules over `mst` → alerts in `aml` → one reconciled **delivery** per business date. The batch for business date D carries postings of D-1 (`posting-offset-days`); rule windows end on the posting day, alerts carry the business date. Java: `promotion/` (batches, validation, partitions), `detect/` (detection, delivery, templates), `rules/` (YAML loading, workflow, dry-run, export), `run/` (JobRunner, JobLock, nightly, health), `web/` (rule UI API and security), `config/`.

**Rule templates.** A rule is data: a `Template` (`detect/*Template.java`, extending `TemplateSupport`) turns a rule's JSON `config` into one set-based SQL (`hitsSql` plus `evidenceSql`, values only as bound parameters). `DetectionService` runs every ACTIVE rule in its own transaction and thread (hits materialised once into a temp table `rule_hits`, `enable_nestloop=off`, per-rule timeout), inserts alerts, links evidence transactions (capped), and emits `WITHDRAWN` events for alerts a re-run no longer produces. A failing rule does not stop the others but keeps the delivery `OPEN` (invisible to the consumer). Adding a template means: the class, `fields()` (drives the UI form; `TemplateFieldsTest` checks it matches the accepted keys), and a rule YAML that uses it (`RuleSpecIT` requires every template to be used).

**Rule lifecycle has two writers.** `specs/rules/*.yml` (loaded by `load-rules`, versioned in `aml.rule`) and the rule UI (`rules/RuleAdminService`: DRAFT → PENDING_APPROVAL → ACTIVE/REJECTED, four-eyes enforced in code and by a DB constraint, approval requires a dry-run recorded after the last edit, append-only `aml.rule_audit`). The database is the source of truth; `load-rules` skips a rule whose latest version has `source = 'UI'`; `export-rules` writes the DB back to YAML keeping each file's `tests:`. Scenarios live only in the YAML files. `RuleSpecIT` runs a DRAFT rule's scenarios as if ACTIVE.

**ML (`fisre-ml/`, Python).** Separate runtime. Features are SQL per account per posting day; one Isolation Forest per product; the percentile score is stored in `aml.ml_score` under the ACTIVE row of `aml.ml_model` (artifact SHA-256 verified before loading the pickle). Java never runs Python: the `ML_SCORE` template only reads `ml_score`. The `ML_ANOMALY` rule ships `status: DRAFT` (shadow mode); if made ACTIVE and no scores exist for the day the rule fails instead of finding nothing. `evaluate` and the pytest fixtures build a synthetic world inside a transaction that is rolled back.

**Delivery contract** (the interface to case management): views `aml.v_alert_*` and functions `confirm_delivery`, `reject_alerts`; payload shape in `docs/alert-payload.schema.json` with real samples in `docs/samples/`. Consumer-facing documents (`docs/case-management-*.md`, the `.docx` copy) must be updated together with the contract; regenerate the `.docx` from the Markdown after edits.

**Spec gate.** `SpecCoverageTest` requires every requirement marked `Implemented` in `specs/requirements/*.md` to be referenced by a `@Req("REQ-...")` test, and every referenced id to exist. It also scans `fisre-ml/tests/*.py` for `# REQ-ML-nnn` comments. New behaviour: spec row first, then test, then code.

**Test data.** `Fixtures` resets everything (partitions, deliveries, rules, audit via the documented purge bypass, ML tables) and inserts master data directly; handed-off alerts and `rule_audit` are immutable except through those bypasses. Tests share one database, so do not run test classes in parallel. Web tests need `@SpringBootTest(properties = "spring.main.web-application-type=servlet")`; the default (and every batch job) is non-web.

## Working agreements

- Spec-driven: change `specs/` first (requirements, ADRs `specs/adr/0001…0010`, data contracts), then the `@Req` test, then code.
- PostgreSQL only for now (ADR-0002). Keep application SQL portable ANSI where it costs nothing; vendor SQL goes in `Dialect`. PostgreSQL-only features are fine in migrations (`db/migration`, V1 to V14; MySQL/Oracle migrations are parked in `db/migration-parked`).
- Set-based SQL, never row-by-row in Java (ADR-0001).
- Scale (ADR-0005): `txn` partitioned by posting day and swapped in; staging is one unlogged partition per batch; never UPDATE staged rows; never put correlated EXISTS or OR-of-subselects in a per-row CASE (use joins or separate queries).
- Batch model: a batch (one business date) succeeds or fails as a whole (ADR-0003, `specs/requirements/batch.md`).
- Rules are configured templates, not code (ADR-0004). No per-rule severity, anywhere (alerts, ML, case-management spec).
- Hardening (ADR-0007): one mutating job at a time (`JobLock`), statement and rule timeouts, handed-off alerts immutable, schema names validated, config values always bound parameters, the app refuses the default DB password (`FISRE_ALLOW_DEFAULT_CREDENTIALS=true` for local runs only).
- Alert delivery (ADR-0008): one reconciled delivery per business date, never one by one; a published delivery is frozen, corrections are new revisions or `WITHDRAWN` events (`specs/data-contract/alert-export.md`). Rejections are reported with a reason and audited, resolved manually (`specs/requirements/rejection.md`).
- Rule UI (ADR-0010) and ML (ADR-0009) decisions are recorded in their ADRs; ML is shadow-mode by default.
- Git: develop on `claude/phase-0-1-foundation`; `main` lags it and changes only through a PR the user opens or asks for.
