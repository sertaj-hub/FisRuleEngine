# ADR-0005: Designed for a billion-row history

Status: accepted

Sizing: 10M transactions per day, 13 months retained (about 4 billion rows). History starts at one month and grows.

- **Dates:** the 1 am batch for business date D carries the transactions posted on D-1 (`posting-offset-days`, default 1). A batch is one posting day, so its transactions live in exactly one partition. Detection windows end on the posting day.
- **Master `txn`:** one range partition per posting day, built offline (insert, then primary key and `(account_id, posting_date)` index, then `ANALYZE`) and swapped in with DETACH/ATTACH in the same transaction as the customer and account upserts. Replacing a day is a partition swap, never a bulk delete. Retention drops old partitions.
- **Uniqueness:** `PRIMARY KEY (transaction_id, posting_date)` per partition, so ids are unique within a posting day. Cross-day duplicates are checked only for the previous `duplicate-lookback-days` (default 3) via the same index. A check against all history would mean probing every partition for every row. No foreign keys on `txn`; validation enforces references.
- **Staging:** one unlogged list partition per batch per entity (no write-ahead log, instant `DROP` on clean), created by a trigger when the ETL registers the batch. The ETL should load straight into its partition (`<table>_b<batch_seq>`).
- **Validation:** one scan per entity with a CASE expression naming the first failing rule; counts and a key sample go to `aml.load_reject`. Staged rows are never updated.
- **Detection:** each template first finds candidate accounts from the as-of day's partition, then aggregates their window with partition pruning and the per-partition account index. Rules run in parallel. Evidence links are capped per alert.
- **History:** loaded as normal batches by `promote-loaded` (earliest alone, then parallel). No detection runs for history.

Limits: `INSERT ... SELECT` into the new partition is single-process in PostgreSQL 16, so a day's build is not parallel inside; parallelism comes from running days concurrently and from parallel index builds. The partition swap takes a short exclusive lock on `txn`, so promotion must not overlap detection.
