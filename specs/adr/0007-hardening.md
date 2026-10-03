# ADR-0007: Hardening decisions

Status: accepted

Scope: the rule engine only. Encryption at rest, network and OS hardening belong to the platform; there is no penetration test here.

- **One mutating job at a time.** A session-level advisory lock, taken on a dedicated connection by the job runner, stops overlapping promote, detect and retention runs (a partition swap needs an exclusive lock on `mst.txn`). The lock is on the whole database, not per job, because the jobs touch overlapping tables. Internal worker threads do not take it.
- **Time limits everywhere.** A connection-level `statement_timeout` (default 1 hour) bounds any statement, and a per-rule limit (default 30 minutes) turns a bad plan into a recorded rule failure instead of a hang. This came from the benchmark, where one plan ran for 20 minutes.
- **Feed completeness.** A day's transaction count is compared with the trailing average of recent promoted days. A feed cut in half would otherwise pass validation and silently miss alerts. The check needs a few days of history, so it stays quiet at first and during history loads.
- **Secrets.** The application refuses the shipped default password. The check runs before the application context starts, so it covers the packaged jar; unit and integration tests are unaffected.
- **Identifiers.** Schema names are the one configuration value used as SQL identifiers, so they are validated against a strict pattern at the point they are read.
- **Audit.** The acting database user is recorded on rule versions, batch registration and alert acknowledgement. Rule history is already versioned and nothing is overwritten.
- **Alert immutability.** A trigger rejects UPDATE and DELETE of an alert once it is handed off, so the guarantee does not depend on engine code. The bypass (`SET aml.allow_alert_purge = 'on'`) exists for a purge under change control. It is a safety net against mistakes, not against a malicious database user: for that, revoke UPDATE and DELETE on `aml.alert` from every role except the owner.
- **Roles.** Partition swaps need ownership of the master tables, so the engine runs as the owner role and a separate migration user adds little. The useful separations are the case management reader, the ETL (insert into staging and register batches only) and the engine owner; `ops/db/postgresql/roles.sql` sets them up.
- **Operations.** A `health` job and `aml.v_ops_*` views let a scheduler or monitoring tool see stuck batches, failed rules, an incomplete nightly run, missing posting days, an unacknowledged-alert backlog and leftover build tables.
- **Supply chain.** Dependabot proposes updates and CI runs an OWASP dependency check.
