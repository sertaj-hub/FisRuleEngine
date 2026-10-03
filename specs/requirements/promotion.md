# Staging to master promotion rules

Applied inside a batch promotion (`batch.md`). Detail: `data-contract/validation-rules.md`.

| ID | Requirement | Status |
|---|---|---|
| REQ-PRM-001 | A clean batch is promoted to master in the order customer, account, transaction, with all columns copied. | Implemented |
| REQ-PRM-002 | A row that fails validation gets `reject_reason` = `<rule id>: <text>`; the first failing rule wins. | Implemented |
| REQ-PRM-003 | If a customer or account key repeats within a batch, the latest staged row wins. | Implemented |
| REQ-PRM-004 | A customer or account that already exists in master is updated, not duplicated. | Implemented |
| REQ-PRM-006 | A child row (account, transaction) whose parent is missing, or is a rejected row of the same batch, is rejected. | Implemented |
| REQ-PRM-007 | Promotion handles 10M transactions in one daily batch within the agreed window. | Planned |
| REQ-PRM-008 | Master transactions and staging are range or list partitioned so cleanup and replacement are partition operations. | Planned |
