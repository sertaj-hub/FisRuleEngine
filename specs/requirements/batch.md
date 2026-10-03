# Batch model

A batch is one business date's delivery of customer, account and transaction rows, identified by `batch_id`.
The bank's ETL registers it in `aml.load_batch`, loads `stg` under that id, then sets it `LOADED`.
A batch **succeeds or fails as a whole**. Protocol and statuses: `data-contract/batch-protocol.md`; ADR-0003.

| ID | Requirement | Status |
|---|---|---|
| REQ-STG-001 | A staging row must carry the id of a registered batch; rows for an unknown batch are refused by the database. | Implemented |
| REQ-BAT-001 | Only a batch in status `LOADED` can be promoted; promoting any other status (including one already promoted or running) is refused. | Implemented |
| REQ-BAT-002 | The whole batch is validated before anything reaches master. If any row is rejected the batch becomes `FAILED`, nothing is promoted, reject reasons stay in stg, and per-entity counts are recorded. | Implemented |
| REQ-BAT-003 | Promotion of a validated batch is atomic across customer, account and transaction: if any step fails, master is unchanged and the batch is `FAILED`. | Implemented |
| REQ-BAT-004 | After a successful promotion the batch's stg rows are deleted and the batch becomes `CLEANED`; its per-entity counts remain in `aml.load_batch_entity`. | Implemented |
| REQ-BAT-005 | `clean` on a `FAILED` batch deletes its stg rows so the ETL can reload. | Implemented |
| REQ-BAT-006 | Promoting a batch for a business date that already has a live batch replaces the earlier batch's transactions atomically and marks it `SUPERSEDED`. At most one live batch per business date, enforced by the database. | Implemented |
| REQ-BAT-007 | A `transaction_id` repeated inside the batch, or already in master from another live batch, fails the batch. | Implemented |
| REQ-BAT-008 | `reopen` returns a `FAILED` (or crashed `PROMOTING`) batch to `LOADED` and clears its reject reasons, so corrected stg rows can be promoted without a new batch id. | Implemented |
| REQ-BAT-009 | A batch with no staged rows fails (an empty delivery must never replace a good batch). | Implemented |
| REQ-BAT-010 | Master history for a superseded batch's customers and accounts is not restored; the corrected batch's values win (upsert). | Planned |
