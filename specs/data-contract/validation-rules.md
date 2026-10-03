# Validation rules (stg to mst)

Applied in order per entity (customer, account, txn); the first failing rule sets `reject_reason`. Any reject fails the whole batch.
Fix by correcting stg and `reopen`, or `clean` and reload under a new batch id. Implemented in `ValidationRule.java`.

| Rule | Entity | Rejects when |
|---|---|---|
| CUS-001 | customer | `customer_id` missing |
| CUS-002 | customer | `customer_type` not INDIVIDUAL or ORGANIZATION |
| CUS-003 | customer | `full_name` missing |
| ACC-001 | account | `account_id` missing |
| ACC-002 | account | `product_type` not CARD, LOAN or DEPOSIT |
| ACC-003 | account | `primary_customer_id` missing, or in neither `mst.customer` nor a valid customer row of the same batch |
| ACC-004 | account | `open_date` missing |
| ACC-005 | account | `status` not ACTIVE, DORMANT, FROZEN or CLOSED |
| TXN-001 | txn | `transaction_id` missing |
| TXN-002 | txn | `account_id` missing, or in neither `mst.account` nor a valid account row of the same batch |
| TXN-003 | txn | `txn_ts` or `posting_date` missing |
| TXN-004 | txn | `amount` missing or not greater than zero (sign is carried by `direction`) |
| TXN-005 | txn | `direction` not DEBIT or CREDIT |
| TXN-006 | txn | `txn_type` not in `mst.ref_txn_type` |
| TXN-007 | txn | `currency` missing |
| TXN-008 | txn | `transaction_id` appears more than once in the batch |
| TXN-009 | txn | `transaction_id` already in `mst.txn` from another batch (the live batch being replaced is excluded) |
