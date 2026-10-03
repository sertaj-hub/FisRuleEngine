# ADR-0003: stg, mst and aml schemas with batch promotion

Status: accepted

- `stg`: landing area written by the bank's ETL. Three entities: customer, account, txn.
- `mst`: validated master data. Detection reads only from here.
- `aml`: engine data: run log now; rules, rule runs and alerts from Phase 2.

Promotion is a daily batch of set-based SQL per entity in one transaction (customer, account, txn), so a failure
leaves rows NEW and a re-run is safe. Rejects stay in stg with a reason; the ETL resends corrected rows as new rows.
Retention: the ETL owns purging of PROCESSED/REJECTED stg rows (engine purge job is a backlog item).
Alert grain: one alert per rule hit, addressed to the primary customer of the account (Phase 2).
