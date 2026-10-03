# Alert export contract (for case management)

Read-only access for the case management system, in schema `aml`:

**View `aml.v_alert_export`**: one row per alert that has not been handed off. Columns (stable; a test guards them):

| Column | Meaning |
|---|---|
| `alert_id` | Unique alert id (bigint) |
| `rule_code` | Stable rule code |
| `business_date` | The batch business date the alert was raised for |
| `account_id`, `product_type` | The account that hit and its product (CARD, LOAN, DEPOSIT) |
| `customer_id` | The account's primary customer (the alert is addressed to this party) |
| `created_ts` | When the alert was created |
| `payload` | JSON, self-contained (below) |

`payload`:
```json
{
  "alert_id": 1, "business_date": "2026-10-01", "summary": "Large cash activity in one day",
  "rule": {"code": "LARGE_CASH_DAILY", "name": "...", "version": 1},
  "account": {"id": "A1", "product_type": "DEPOSIT"},
  "customer": {"id": "C1", "name": "...", "type": "INDIVIDUAL", "country": "US", "state": "NY"},
  "evidence": {"total": 11000, "txn_count": 2, "window_days": 1, "...": "rule-specific"},
  "transactions": [{"transaction_id": "T1", "posting_date": "2026-09-30", "txn_ts": "...", "txn_type": "CASH_DEPOSIT",
                    "direction": "CREDIT", "amount": 6000, "currency": "USD", "channel": null,
                    "counterparty_name": null, "counterparty_country": null}]
}
```
The customer block is a snapshot taken when the alert was created. `transactions` is capped per alert (`max-evidence-txns`); `evidence` carries the true totals.

**Function `aml.ack_alerts(alert_ids bigint[]) returns integer`**: call it after ingesting. It marks the alerts as handed off, returns how many were newly marked, and is safe to repeat. Acknowledged alerts leave the view and are never altered by detection re-runs.

Typical intake loop: `SELECT alert_id, payload FROM aml.v_alert_export ORDER BY alert_id LIMIT 1000`; ingest; `SELECT aml.ack_alerts(ARRAY[...])`.
Grants: a read-only role needs `SELECT` on the view and `EXECUTE` on the function only (`ops/db/postgresql/case_mgmt_grants.sql`).
