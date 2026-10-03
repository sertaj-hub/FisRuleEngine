# Batch model

A batch is one business date's delivery of customer, account and transaction rows, identified by `batch_id`.
The bank's ETL registers it in `aml.load_batch`, loads `stg` under that id, then sets it `LOADED`.
A batch **succeeds or fails as a whole**. Protocol and statuses: `data-contract/batch-protocol.md`; ADR-0003.

| ID | Requirement | Status |
|---|---|---|
| REQ-STG-001 | Registering a batch creates its three staging partitions (unlogged, one per entity). Staging rows can only be loaded for a registered batch; a row for any other batch id is refused by the database. | Implemented |
| REQ-BAT-001 | Only a batch in status `LOADED` can be promoted; promoting any other status (including one already promoted or running) is refused. | Implemented |
| REQ-BAT-002 | The whole batch is validated before anything reaches master, with one pass per entity and without modifying staged rows. If any row is rejected the batch becomes `FAILED`, nothing is promoted, and `aml.load_reject` records, per entity and rule, the reject count, the reason and a sample of keys. | Implemented |
| REQ-BAT-003 | Promotion of a validated batch is atomic across customer, account and transaction: if any step fails, master is unchanged and the batch is `FAILED`. | Implemented |
| REQ-BAT-004 | After a successful promotion the batch's staging partitions are dropped and the batch becomes `CLEANED`; its per-entity counts remain in `aml.load_batch_entity`. | Implemented |
| REQ-BAT-005 | `clean` on a `FAILED` batch drops its staging partitions; the ETL then reloads under a new batch id. | Implemented |
| REQ-BAT-006 | Promoting a batch for a business date that already has a live batch replaces the earlier batch's transactions atomically by swapping the posting day's partition, and marks it `SUPERSEDED`. At most one live batch per business date, enforced by the database. | Implemented |
| REQ-BAT-007 | A `transaction_id` repeated inside the batch, or present in the previous `duplicate-lookback-days` (default 3) posting days, fails the batch. Uniqueness within a posting day is also enforced by the partition's primary key; older duplicates are not checked (a global check against all history is not feasible). | Implemented |
| REQ-BAT-008 | `reopen` returns a `FAILED` (or crashed `PROMOTING`) batch whose staging has not been cleaned to `LOADED` and clears its reject records, so corrected staging rows can be promoted without a new batch id. | Implemented |
| REQ-BAT-009 | A batch with no staged rows fails (an empty delivery must never replace a good batch). | Implemented |
| REQ-BAT-010 | Master history for a superseded batch's customers and accounts is not restored; the corrected batch's values win (upsert). | Planned |
| REQ-BAT-011 | Every transaction's `posting_date` must equal the batch's business date minus `posting-offset-days` (default 1: the 1 am batch for business date D carries the transactions posted on D-1). | Implemented |
