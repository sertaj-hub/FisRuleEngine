# Batch protocol (ETL and engine)

One `batch_id` per business date, covering customer, account and txn. The ETL owns steps 1 to 3 and the engine owns 4 to 6.

1. Register: `INSERT INTO aml.load_batch (batch_id, business_date, status) VALUES (:id, :date, 'LOADING')`.
2. Load `stg.customer`, `stg.account`, `stg.txn` with that `batch_id` (customers and accounts are daily deltas; transactions are new).
3. Signal complete: `UPDATE aml.load_batch SET status = 'LOADED', loaded_ts = CURRENT_TIMESTAMP WHERE batch_id = :id`.
4. Engine `FISRE_JOB=promote FISRE_BATCH_ID=<id>`: validate everything; any reject fails the batch, otherwise one transaction replaces any earlier live batch for the date and loads master.
5. On success the engine deletes the batch's stg rows (`CLEANED`).
6. On failure (`FAILED`, exit code non-zero):
   - correct stg and run `reopen`, then `promote`; or
   - run `clean`, and reload under a **new** batch id for the same business date.

Status flow: `LOADING` → `LOADED` → `PROMOTING` → `PROMOTED` → `CLEANED`; `PROMOTING` → `FAILED`; `PROMOTED`/`CLEANED` → `SUPERSEDED` when a later batch for the same date is promoted.
A crashed run leaves `PROMOTING`; use `reopen`.

Correcting master after the fact: load a new batch for the same business date and promote it. Its transactions replace the earlier batch's; customers and accounts are upserted (earlier versions are not restored).
