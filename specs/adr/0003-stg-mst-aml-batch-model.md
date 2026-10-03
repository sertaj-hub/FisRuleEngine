# ADR-0003: stg, mst and aml schemas with an all-or-nothing batch

Status: accepted (revised with the batch-id model)

- `stg`: landing area written by the bank's ETL under a registered `batch_id`. Entities: customer, account, txn.
- `mst`: validated master data. Detection reads only from here.
- `aml`: `load_batch` (+ `load_batch_entity` audit counts) now; rules, rule runs and alerts from Phase 2.

A batch is one business date. It is validated as a whole, promoted in one transaction, then its stg rows are deleted. Any reject fails it entirely.
Failure is repaired by correcting stg and `reopen`, or by clean and reload under a new id. Correction after promotion is a new batch for the same date that replaces the old one's transactions.

Trade-offs: one bad row blocks the day's load (chosen deliberately: no partial, hard-to-reason-about master data); customers and accounts are upserts so they are not rolled back to earlier versions (REQ-BAT-010 planned);
deleting millions of stg rows is slow, so cleanup is a separate transaction and partitioning is a Phase 4 item.
Alert grain: one alert per rule hit, addressed to the primary customer of the account.
