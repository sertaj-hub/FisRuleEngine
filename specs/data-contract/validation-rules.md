# Validation rules (stg to mst)

Applied in order per entity; the first failing rule sets `reject_reason`. Rejected rows are final:
the ETL must send a corrected row as a new row. Implemented in `ValidationRule.java`.

| Rule | Entity | Rejects when |
|---|---|---|
| CUS-001 | customer | `customer_id` missing |
| CUS-002 | customer | `customer_type` not INDIVIDUAL or ORGANIZATION |
| CUS-003 | customer | `full_name` missing |
| ACC-001 | account | `account_id` missing |
| ACC-002 | account | `product_type` not CARD, LOAN or DEPOSIT |
| ACC-003 | account | `primary_customer_id` missing or not in `mst.customer` |
| ACC-004 | account | `open_date` missing |
| ACC-005 | account | `status` not ACTIVE, DORMANT, FROZEN or CLOSED |
| TXN-001 | txn | `transaction_id` missing |
| TXN-002 | txn | `account_id` missing or not in `mst.account` |
| TXN-003 | txn | `txn_ts` or `posting_date` missing |
| TXN-004 | txn | `amount` missing or not greater than zero (sign is carried by `direction`) |
| TXN-005 | txn | `direction` not DEBIT or CREDIT |
| TXN-006 | txn | `txn_type` not in `mst.ref_txn_type` |
| TXN-007 | txn | `currency` missing |
