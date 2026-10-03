# Staging to master promotion

The bank's ETL loads `stg.customer`, `stg.account` and `stg.txn`. A daily batch validates and promotes them to `mst`.
Detail: `data-contract/validation-rules.md`, ADR-0003.

| ID | Requirement | Status |
|---|---|---|
| REQ-STG-001 | Staging tables accept customer, account and transaction rows; a row without an explicit status is `NEW`. | Implemented |
| REQ-PRM-001 | Valid `NEW` rows are promoted to master in the order customer, account, transaction, and marked `PROCESSED`. | Implemented |
| REQ-PRM-002 | A row that fails validation is marked `REJECTED` with `reject_reason` = `<rule id>: <text>` and is never promoted. | Implemented |
| REQ-PRM-003 | If a key appears more than once in a batch, the latest row wins for customer and account. A transaction id already in master is never overwritten (first delivery wins). | Implemented |
| REQ-PRM-004 | A customer or account that already exists in master is updated, not duplicated. | Implemented |
| REQ-PRM-005 | Promotion is idempotent: re-running with no `NEW` rows changes nothing. | Implemented |
| REQ-PRM-006 | A child row (account, transaction) whose parent is missing or was rejected is rejected. | Implemented |
| REQ-PRM-007 | Promotion runs in set-based SQL and handles 10M transactions in one daily run within the agreed window. | Planned |
| REQ-PRM-008 | Master transactions are range-partitioned by posting date on each vendor. | Planned |
