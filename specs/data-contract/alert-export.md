# Alert export contract (for case management)

Read-only access to schema `aml`, plus three functions. One **delivery** per business date; the consumer reads published deliveries, ingests them, and confirms with control totals (ADR-0008). Alerts are never pushed one by one: a date is visible only when detection for it has finished.

## Delivery lifecycle

`OPEN` (being built, invisible) → `READY` (published) → `CONFIRMED` (your numbers matched) or `MISMATCH` (they did not; the alerts stay visible so you can read them again).

A re-run for a date that already has a published delivery never changes it. New alerts form the next **revision** of that date; alerts that no longer hit are reported as `WITHDRAWN` events, never deleted.

## Objects

**`aml.v_alert_delivery`**: published deliveries. `delivery_id`, `business_date`, `revision`, `status`, `alert_count`, `rule_counts` (JSON per rule), `checksum`, `ready_ts`, `confirmed_ts`. Ingest deliveries with status `READY` or `MISMATCH`.

**`aml.v_alert_export`**: one row per unsent alert of a `READY`/`MISMATCH` delivery. Columns (stable; a test guards them): `alert_id`, `delivery_id`, `rule_code`, `business_date`, `account_id`, `customer_id` (the account's primary customer, who the alert is addressed to), `product_type`, `created_ts`, `payload`.

`payload` (JSON, self-contained; the customer block is a snapshot taken when the alert was created; `transactions` is capped per alert, `evidence` carries the true totals):
```json
{
  "alert_id": 1, "business_date": "2026-10-01", "summary": "Large cash activity in one day",
  "delivery": {"id": 7, "revision": 1},
  "rule": {"code": "LARGE_CASH_DAILY", "name": "...", "version": 1},
  "account": {"id": "A1", "product_type": "DEPOSIT"},
  "customer": {"id": "C1", "name": "...", "type": "INDIVIDUAL", "country": "US", "state": "NY"},
  "evidence": {"total": 11000, "txn_count": 2, "window_days": 1},
  "transactions": [{"transaction_id": "T1", "posting_date": "2026-09-30", "txn_ts": "...", "txn_type": "CASH_DEPOSIT",
                    "direction": "CREDIT", "amount": 6000, "currency": "USD", "channel": null,
                    "counterparty_name": null, "counterparty_country": null}]
}
```

**`aml.v_alert_events`**: `event_id` (increasing), `alert_id`, `event_type` (`WITHDRAWN`), `reason`, `created_ts`, `rule_code`, `business_date`, `account_id`, `delivery_id`. A `WITHDRAWN` event means a re-run on corrected data no longer produces an alert you already received. Keep the highest `event_id` you processed and read newer ones.

**`aml.v_alert_reconciliation`**: per delivery: `expected_count`, `created_alerts`, `acknowledged_alerts`, `pending_alerts`, `rejected_unresolved`, `withdrawn_alerts`, `received_count`, `checksum`, `received_checksum`, `hours_unconfirmed`. For daily reconciliation by both sides.

## Functions

- **`aml.confirm_delivery(delivery_id bigint, received_count integer, received_checksum text) returns text`**: call it after ingesting a whole delivery. Returns `CONFIRMED` (numbers match; every alert of the delivery is marked handed off) or `MISMATCH` (recorded with your numbers; nothing is marked, the alerts stay visible). Repeating a matching confirmation returns `CONFIRMED` again. An `OPEN` or unknown delivery raises an error.
- **`aml.reject_alerts(alert_ids bigint[], reason text) returns integer`**: tell us which alerts you could not ingest and why. Recorded with your database user; returns how many were recorded. The engine team follows up (visible in `health` and the reconciliation view).
- **`aml.compute_alert_checksum(alert_ids bigint[]) returns text`**: the checksum formula, so both sides agree. It is the SHA-256, lowercase hex, of the alert ids sorted ascending and joined with commas (no spaces; an empty list gives the SHA-256 of the empty string).
- `aml.ack_alerts(alert_ids bigint[]) returns integer` (optional): mark individual alerts handed off while you ingest. `confirm_delivery` makes it unnecessary.

## Intake loop

1. `SELECT delivery_id, alert_count, checksum FROM aml.v_alert_delivery WHERE status IN ('READY','MISMATCH') ORDER BY business_date, revision`.
2. For one delivery: `SELECT alert_id, payload FROM aml.v_alert_export WHERE delivery_id = :id ORDER BY alert_id` (page by `alert_id`). Ingest idempotently, keyed by `alert_id`.
3. Compute `count` and `checksum` from the alert ids you actually stored, then `SELECT aml.confirm_delivery(:id, :count, :checksum)`. If it returns `MISMATCH`, compare with `v_alert_reconciliation`, re-read, and confirm again.
4. Alerts you cannot ingest: `SELECT aml.reject_alerts(ARRAY[...], 'reason')`, and do not include them in your count (the delivery will then show a mismatch until the engine team resolves it).
5. Read `v_alert_events` for events newer than the last `event_id` you processed.

Grants: `SELECT` on the five views and `EXECUTE` on the functions only (`ops/db/postgresql/case_mgmt_grants.sql`).
