# Hardening: safety, security, audit and operations

Design record: ADR-0007. Operating guidance: `ops/RUNBOOK.md`, `ops/BACKUP_RESTORE.md`.

| ID | Requirement | Status |
|---|---|---|
| REQ-OPS-001 | Mutating engine jobs (everything except `none` and `health`) take a database-wide lock; a second one fails fast with a clear message, and the lock is released when the first ends. | Implemented |
| REQ-OPS-002 | Every database statement is bounded by a timeout (`FISRE_STATEMENT_TIMEOUT`, default 1 h on every connection), and each rule runs under its own `rule-timeout-seconds` (default 30 min); a timed-out rule is recorded `FAILED` in `aml.rule_run` and does not stop the others. | Implemented |
| REQ-BAT-012 | When at least `volume-check-min-days` (3) of the previous `volume-check-days` (7) business days have a promoted batch, a batch whose transaction count is below `volume-low-percent` (50) of their average fails with rule `VOL-001` recorded in `aml.load_reject`; above `volume-high-percent` (200) it only logs a warning. `volume-check-days` = 0 turns the check off. | Implemented |
| REQ-SEC-001 | The packaged application refuses to start with the default database password unless `FISRE_ALLOW_DEFAULT_CREDENTIALS=true` (local development). | Implemented |
| REQ-SEC-002 | Schema names from configuration must be plain lowercase identifiers (letters, digits, underscore, at most 63 characters, not starting with a digit); anything else stops startup. | Implemented |
| REQ-SEC-003 | Hostile text in batch ids, rule names, rule descriptions and rule filter values is only ever data: it never changes the structure of a statement. | Implemented |
| REQ-AUD-001 | Rule versions record the database user that loaded them, batches the user that registered them, and acknowledged alerts the user that acknowledged them. | Implemented |
| REQ-AUD-002 | A handed-off alert cannot be updated or deleted (database trigger), and detection re-runs add no transaction links to it; a purge needs the explicit, documented bypass setting. | Implemented |
| REQ-HLT-001 | `health` reports, with a severity, each of: a batch stuck in `PROMOTING` too long (CRITICAL); a rule failure not later succeeded for the same date (CRITICAL); an incomplete nightly run for a given business date (CRITICAL); unacknowledged alerts older than `health-ack-hours` (WARN); missing posting days inside the loaded range (WARN); leftover `txn_new_*` build tables (WARN). | Implemented |
| REQ-HLT-002 | `health` on a healthy system reports nothing, and the job exits non-zero only when a CRITICAL finding exists. | Implemented |
| REQ-HLT-003 | Views `aml.v_ops_batches`, `aml.v_ops_failures` and `aml.v_ops_alert_backlog` give operations the recent batches, recent failures and the unsent-alert backlog. | Implemented |
