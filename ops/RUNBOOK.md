# Operations runbook

All commands run the same jar: `java -jar fisre-engine.jar` with `FISRE_JOB=<job>` and the database settings in `FISRE_DB_*` (see README). Every run applies pending schema migrations first. A non-zero exit code means the job failed; the log and the tables below say why.

## Every night (1 am batch for business date D, carrying transactions posted on D-1)

1. The bank's ETL registers the batch: `INSERT INTO aml.load_batch (batch_id, business_date, status) VALUES ('<id>', DATE 'D', 'LOADING')`. This creates the batch's staging partitions `customer_b<seq>`, `account_b<seq>`, `txn_b<seq>` (`SELECT batch_seq FROM aml.load_batch WHERE batch_id = '<id>'`).
2. The ETL loads them (COPY straight into the partitions is fastest), then sets `status = 'LOADED'`.
3. The scheduler runs one command: `FISRE_JOB=nightly FISRE_BATCH_ID=<id> FISRE_BUSINESS_DATE=D java -jar fisre-engine.jar`
   It promotes, detects and applies retention, in that order, and stops at the first failing step. Progress: `SELECT * FROM aml.nightly_run WHERE business_date = DATE 'D' ORDER BY run_id`.
4. Detection publishes **one delivery for the business date** when every rule has succeeded (`READY`, with alert count, count per rule and checksum; zero alerts is still published). Case management reads `aml.v_alert_delivery`, ingests the delivery's alerts from `aml.v_alert_export`, and calls `aml.confirm_delivery(id, count, checksum)`; the engine answers `CONFIRMED` or `MISMATCH`. Contract: `specs/data-contract/alert-export.md`; grants: `ops/db/postgresql/case_mgmt_grants.sql`; design: ADR-0008.

Re-running `nightly` for the same batch after a later step failed is safe: promote is skipped, detect replaces unsent alerts, handed-off alerts are never touched.

## When a step fails

| Symptom | Look at | Action |
|---|---|---|
| PROMOTE failed: "failed validation" | `SELECT * FROM aml.load_reject WHERE batch_id = '<id>'` (rule, count, sample keys) | Fix the rows in staging and run `FISRE_JOB=reopen`, then `nightly` again; or run `clean` and reload under a **new** batch id for the same date |
| PROMOTE failed: "Batch is empty" | `aml.load_batch_entity` | The ETL delivered nothing; reload |
| Crashed run, batch stuck in `PROMOTING` | `aml.load_batch` | `FISRE_JOB=reopen`, then `nightly` |
| DETECT failed: "rule(s) failed" | `SELECT * FROM aml.rule_run WHERE status = 'FAILED'` (error_msg) | Fix the rule spec, `load-rules`, rerun `detect` for the date |
| "No live (promoted) batch for business date" | `aml.load_batch` | Detection needs a promoted batch for that date |
| DELIVERY_NOT_CONFIRMED (health) | `SELECT * FROM aml.v_alert_reconciliation ORDER BY business_date DESC` | A published delivery has not been confirmed within `FISRE_HEALTH_CONFIRM_HOURS` (12). Ask case management whether they ingested it; they confirm, or reject alerts with a reason |
| DELIVERY_MISMATCH (health) | `v_alert_reconciliation`: `expected_count`, `received_count`, `checksum`, `received_checksum` | Counts differ: they missed or double-loaded alerts. Same count, different checksum: different alerts. They read the delivery again (it stays visible) and confirm again |
| ALERT_REJECTED (health) | `SELECT * FROM aml.alert_rejection WHERE resolved_ts IS NULL` | Case management could not ingest these alerts. Fix the cause (usually a data issue such as an unknown customer), tell them to re-read, then `UPDATE aml.alert_rejection SET resolved_ts = CURRENT_TIMESTAMP, resolved_by = current_user WHERE ...` |
| DELIVERY_NOT_PUBLISHED, delivery stays `OPEN` | `aml.rule_run` for the date (a rule failed) | Fix the rule, `load-rules`, rerun `detect`; the same delivery is then published. Nothing of an OPEN delivery is visible to case management |
| A corrected delivery for a past date | n/a | Load it as a new batch for the same date and promote it; it replaces the old day's transactions. Re-run `detect` for that date: published alerts never change; new hits become the next revision of that date's delivery, and alerts that no longer hit are reported to case management as `WITHDRAWN` events (`aml.v_alert_events`) |

## One-off jobs

- Load or change rules: edit `specs/rules/*.yml`, then `FISRE_JOB=load-rules FISRE_RULES_DIR=<dir>`. Unchanged rules get no new version.
- Initial history (one month, later up to 13): ETL loads one batch per day (earliest carries the full customer and account snapshot); then `FISRE_JOB=promote-loaded` (earliest first, rest in parallel, `FISRE_PARALLELISM`). Do not run detect for history.
- Retention: part of `nightly`; or `FISRE_JOB=retain FISRE_BUSINESS_DATE=D` (keeps 13 months, `FISRE_RETENTION_MONTHS`).
- Synthetic data for demos and tests: `FISRE_JOB=generate` (see README).

## Monitoring and health

- `FISRE_JOB=health [FISRE_BUSINESS_DATE=D]` (read-only, no lock) prints findings and exits non-zero only for CRITICAL ones: a batch stuck in `PROMOTING`, a rule that failed and has not succeeded since, and (with a date) an incomplete nightly run. WARN findings are an unacknowledged-alert backlog, missing posting days inside the loaded range, and leftover `txn_new_*` build tables. Run it after the nightly job from the scheduler and alert on a non-zero exit.
- Views for dashboards: `aml.v_ops_batches` (last 45 days, with transaction counts), `aml.v_ops_failures` (last 7 days of failed batches, rules and nightly steps), `aml.v_ops_alert_backlog` (unsent alerts per business date).
- A new batch whose transaction count is below 50% of the trailing 7-day average fails with `VOL-001` (`aml.load_reject`): a feed that was cut short. If the day really was that quiet, reload it after agreeing the exception, or set `FISRE_VOLUME_LOW_PERCENT` lower for that run.

## Safety limits

- Only one mutating engine job runs at a time. If a job refuses to start with "another engine job holds the database lock", wait for the running job (see `aml.nightly_run`); the lock disappears with the process, even after a crash.
- Every statement is bounded by `FISRE_STATEMENT_TIMEOUT` (default 1h) and each rule by `FISRE_RULE_TIMEOUT_SECONDS` (default 1800). A rule that times out is recorded `FAILED` in `aml.rule_run`.
- The packaged application refuses the default database password. Set `FISRE_DB_PASSWORD`; `FISRE_ALLOW_DEFAULT_CREDENTIALS=true` is for local development only.
- Roles and least privilege: `ops/db/postgresql/roles.sql`. Handed-off alerts are immutable (trigger); a purge under change control uses `SET aml.allow_alert_purge = 'on'` on that session, and should be agreed with compliance first.
- Backup and restore: `ops/BACKUP_RESTORE.md`.

## Database settings that matter

Parallel query and index builds drive promote and detect speed: `max_parallel_workers_per_gather` (4 or more), `max_parallel_maintenance_workers`, `work_mem` (64 MB or more for the validation and detection joins), `shared_buffers` (25% of RAM), `maintenance_work_mem` (1 GB or more for index builds). Staging tables are unlogged (no WAL), master partitions are logged.
