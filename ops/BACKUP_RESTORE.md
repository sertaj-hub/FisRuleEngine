# Backup and restore guidance

Applies to the PostgreSQL database the engine uses. This is guidance, not a tested procedure: run your own restore drill (below) before relying on it.

## What matters

| Data | Where | Back up? | Why |
|---|---|---|---|
| Alerts, evidence and links, acknowledgements | `aml.alert`, `aml.alert_txn` | **Yes, always** | The record of what was raised and handed to case management. Cannot be regenerated exactly (rules and data change). |
| Rule versions and run history | `aml.rule`, `aml.rule_run`, `aml.nightly_run` | **Yes** | Explains why an alert exists; audit trail. |
| Batch records and rejects | `aml.load_batch`, `aml.load_batch_entity`, `aml.load_reject` | **Yes** | Load history, counts used by the volume check. |
| Master data | `mst.customer`, `mst.account`, `mst.txn_*` | **Yes** | 13 months are needed for windows and baselines; rebuilding means reloading every day's batch. |
| Staging | `stg.*` | **No** | Unlogged on purpose; the ETL can reload. After a crash these tables are empty, which is expected. |
| Schema history | `aml.flyway_schema_history` | Yes | Migration state. |

## Recommended setup
- Continuous WAL archiving with point-in-time recovery, plus a periodic base backup (for example weekly). Unlogged staging is excluded automatically.
- A logical dump of the `aml` schema before every release as a second, portable copy: `pg_dump --schema=aml --format=custom`.
- Master partitions are independent tables: a single day can be restored from a base backup or a per-partition dump (`pg_dump --table=mst.txn_20260930`) and attached again, or simply reloaded by running a new batch for that date.
- Keep backups at least as long as the alert retention period you decide with compliance.

## Restore drill (do this once, then yearly)
1. Restore the latest base backup and WAL to a separate server, to a time you choose.
2. Start the engine against it with `FISRE_JOB=health`: expect no CRITICAL findings.
3. Check counts: `SELECT business_date, status FROM aml.load_batch ORDER BY 1 DESC LIMIT 5`, and `SELECT COUNT(*) FROM aml.alert`.
4. Confirm the alert view returns data: `SELECT COUNT(*) FROM aml.v_alert_export`.
5. Run `FISRE_JOB=detect` for the latest business date in a scratch copy and confirm it reproduces the same alerts as the original run.
6. Write down how long it took, and what was missing.

## After a restore to an earlier point
Batches and alerts after that point are gone from the database. Re-register and reload the missed days' batches in date order and run `nightly` for each. Alerts that case management already acknowledged before the failure but that the restore rolled back are the main risk: compare `aml.v_alert_export` with what case management holds, and agree who re-sends what.
