# Entities

Three entities per ADR-0003. The same business columns exist in `stg` and `mst`.
`stg` adds `stg_id`, `load_id`, `rec_status` (NEW, PROCESSED, REJECTED) and `reject_reason`.
`mst` adds `load_id`, `created_ts`, `updated_ts`. Source of truth for types: `db/migration/<vendor>/`.

**customer** (`customer_id`): `customer_type`, `full_name`, `birth_or_formation_dt`, `country_code`, `state_code`, `customer_since`, `status`

**account** (`account_id`): `primary_customer_id`, `product_type` (CARD, LOAN, DEPOSIT), `product_subtype`, `status`, `open_date`, `close_date`, `currency`, `branch_code`, `credit_limit`

**txn** (`transaction_id`): `account_id`, `txn_ts`, `posting_date`, `txn_type` (see `mst.ref_txn_type`, which also flags cash types), `direction`, `amount`, `currency`, `channel`, `counterparty_name`, `counterparty_account`, `counterparty_country`, `merchant_category_code`, `description`

Notes
- Detection runs on accounts, then their transactions. An alert goes to the account's **primary** customer only; joint owners are out of scope.
- Amounts are positive; `direction` gives the sign.
- Oracle stores an empty string as NULL; the rules treat both as missing.
- `txn` is the table name (not `transaction`, a reserved word on some databases).
