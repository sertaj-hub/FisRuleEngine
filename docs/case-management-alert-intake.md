# Alert intake: how case management reads alerts and reconciles them

Functional and design document. Audience: the case management team, the AML detection engine team, operations and compliance.
Status: describes what the rule engine implements today. Items marked **Open** need a decision (section 14).

---

## 1. Purpose and scope

The AML rule engine detects suspicious activity on card, loan and deposit accounts every night and raises **alerts**. Case management (the "consumer") turns alerts into cases for investigation and, where warranted, SAR filing.

This document defines **how the consumer gets the alerts and proves it has all of them**:

- how it learns that alerts are available,
- how it reads them (the interface and the payload),
- how both sides reconcile counts and content,
- how corrections, rejections and failures are handled,
- who does what when something goes wrong.

Out of scope: how the consumer creates cases, assigns work or files SARs, and the detection rules themselves.

**Design principle:** alerts move in **one reconciled delivery per business date**, not one by one. Volume is small (roughly 300 to 5,000 alerts a day) and all of a day's alerts are created within minutes of each other, so the design optimises for completeness and proof, not throughput.

## 2. Context

```mermaid
flowchart LR
  ETL[Bank ETL] -->|1 am batch: yesterday's transactions| ENG[AML rule engine]
  ENG -->|writes| OUT[(Delivery tables<br/>and views in schema aml)]
  CM[Case management] -->|poll or LISTEN, read| OUT
  CM -->|confirm / reject<br/>functions, or out of band| OUT
  OPS[Operations] -->|health job, reconciliation view| OUT
```

| Actor | Responsibility |
|---|---|
| Rule engine | Detects, builds and publishes deliveries, freezes them, answers confirmations, reports health |
| Case management | Detects availability, reads, ingests idempotently, confirms or rejects, reports its own reconciliation |
| Operations | Watches health findings, chases unconfirmed or mismatched deliveries |
| Engine team | Fixes data or rule problems behind rejections and mismatches |

## 3. Key concepts

| Term | Meaning |
|---|---|
| **Business date** | The date of the nightly batch (for example 2026-10-01). It carries the transactions posted on the previous day (2026-09-30). Alerts carry the business date. |
| **Delivery** | The set of alerts for one business date, published together. Identified by `delivery_id`. |
| **Revision** | A date can have more than one delivery if a correction produces new alerts after publication: revision 1, 2, ... Published deliveries are never changed. |
| **Control totals** | Written by the engine when it publishes: alert count, count per rule, checksum. |
| **Checksum** | SHA-256, lowercase hex, of the delivery's alert ids sorted ascending and joined with commas (`101,102,250`). An empty delivery gives the SHA-256 of the empty string. |
| **Primary customer** | The alert is addressed to the primary party of the account. Joint owners are not modelled. |
| **WITHDRAWN event** | Tells the consumer that an alert it received would no longer be raised after a correction. The alert itself is never altered or deleted. |
| **Rejection** | The consumer tells the engine it could not ingest particular alerts, with a reason. |

### Delivery status

```mermaid
stateDiagram-v2
  [*] --> OPEN: detection starts for the date
  OPEN --> READY: every rule succeeded, control totals written
  OPEN --> OPEN: a rule failed (stays invisible), next run retries
  READY --> CONFIRMED: consumer numbers match
  READY --> MISMATCH: consumer numbers differ
  MISMATCH --> CONFIRMED: consumer reads again and numbers match
```

- `OPEN` is invisible to the consumer: a half-built day is never exposed.
- A date with **no alerts** is still published (`READY`, count 0), so "nothing found" can be told apart from "not run yet".
- Alerts of a `READY` delivery stay visible until the delivery is `CONFIRMED`.

## 4. How alerts become available

### 4.1 Timeline of a normal night

```mermaid
sequenceDiagram
  participant ETL
  participant Engine
  participant DB as Delivery tables
  participant CM as Case management
  ETL->>Engine: batch for business date D loaded (about 1 am)
  Engine->>DB: promote data, run all rules
  Engine->>DB: delivery D = OPEN, then READY with control totals
  DB-->>CM: NOTIFY aml_delivery_ready (optional hint)
  CM->>DB: poll v_alert_delivery (or react to the hint)
  CM->>DB: read alerts in pages, ingest idempotently
  CM->>DB: confirm_delivery(id, count, checksum)
  DB-->>CM: CONFIRMED (or MISMATCH)
```

The engine promotes the data and runs the rules in a single nightly job. In its 5-million-transaction test on a small server (not production) the engine's work took well under two minutes in total; the real time to READY depends on production volume and hardware and must be measured (**Open**: agree the "ready by" time, see section 14).

### 4.2 Availability signals (use any one; polling always works)

| Signal | How | Notes |
|---|---|---|
| **Poll (recommended default)** | `SELECT ... FROM aml.v_alert_delivery WHERE status IN ('READY','MISMATCH')` | Simple, no connection held open, survives restarts. Poll every 5 to 15 minutes from about 1 am until the day's delivery is confirmed, then hourly. |
| **Notification (optional hint)** | `LISTEN aml_delivery_ready;` The engine sends a JSON payload `{"delivery_id": 12, "business_date": "2026-10-01", "revision": 1, "alert_count": 340}` when a delivery is published. | Lost if the listener is disconnected, so it is a hint only: keep a slow poll as the safety net. Needs a long-lived database connection. |
| **Out of band** | Any other channel the two teams agree (message, e-mail, ticket) telling the consumer a delivery is ready. | The consumer then reads exactly as in section 5; the signal does not change the protocol. A relay that sends such a message from the delivery tables can be added without changing detection. |

## 5. Reading and ingesting: step by step

```mermaid
flowchart TD
  A[Find deliveries READY or MISMATCH] --> B{Any?}
  B -- no --> Z[Wait, poll again]
  B -- yes --> C[Take the oldest: by business date, then revision]
  C --> D[Read its alerts in pages by alert_id]
  D --> E[Ingest each alert idempotently, key = alert_id]
  E --> F{Any alert cannot be ingested?}
  F -- yes --> G[reject_alerts with reason, exclude from count]
  F -- no --> H[Compute count and checksum from alert ids actually stored]
  G --> H
  H --> I[confirm_delivery]
  I --> J{CONFIRMED?}
  J -- yes --> K[Read WITHDRAWN events, done for this delivery]
  J -- no --> L[Compare with v_alert_reconciliation, read again, confirm again]
```

### 5.1 Find what is ready
```sql
SELECT delivery_id, business_date, revision, status, alert_count, rule_counts, checksum, ready_ts
FROM aml.v_alert_delivery
WHERE status IN ('READY', 'MISMATCH')
ORDER BY business_date, revision;
```

### 5.2 Read one delivery in pages
```sql
SELECT alert_id, payload
FROM aml.v_alert_export
WHERE delivery_id = :delivery_id AND alert_id > :last_seen_alert_id
ORDER BY alert_id
LIMIT 1000;
```
Page size 500 to 1,000. Keep the last `alert_id` of each page. The view only returns alerts of published deliveries that have not been confirmed, so a restart simply continues; re-reading an alert is always safe.

### 5.3 Ingest idempotently
Key everything on `alert_id`: inserting the same alert twice must not create two cases. The payload is self-contained (section 6); the consumer does not need to read any other engine table.

### 5.4 Confirm
Compute, from the alert ids **you actually stored for this delivery**:
- `count` = how many,
- `checksum` = SHA-256 (hex) of the ids sorted ascending, joined by commas. `SELECT aml.compute_alert_checksum(ARRAY[...]::bigint[])` returns the same value, so both sides can verify their implementation.

```sql
SELECT aml.confirm_delivery(:delivery_id, :count, :checksum);   -- 'CONFIRMED' or 'MISMATCH'
```
- **CONFIRMED**: the numbers equal the control totals. The engine marks every alert of the delivery as handed off; the delivery leaves the polling list. Repeating the same confirmation returns `CONFIRMED` again.
- **MISMATCH**: the engine records what you sent and leaves the alerts visible so you can read them again. Nothing is lost.

### 5.5 Reject what you cannot ingest
```sql
SELECT aml.reject_alerts(ARRAY[101, 102]::bigint[], 'customer C1 not found in case management');
```
Rejected alerts stay visible. Leave them out of your count: the delivery then shows a mismatch, which keeps it open until the cause is fixed (section 7.4). Rejections are recorded with the reason, your database user and the time, and operations see them in the health report.

### 5.6 Read events (corrections)
```sql
SELECT event_id, alert_id, event_type, reason, created_ts, rule_code, account_id
FROM aml.v_alert_events
WHERE event_id > :last_processed_event_id
ORDER BY event_id;
```
Keep the highest `event_id` you processed. `WITHDRAWN` means a re-run on corrected data would no longer raise an alert you already received. The consumer decides what to do (typically flag the alert or case for review); the engine never edits or deletes a delivered alert.

## 6. Payload specification

`payload` (JSON) is the full alert. The scalar columns of `v_alert_export` (`alert_id`, `delivery_id`, `rule_code`, `business_date`, `account_id`, `customer_id`, `product_type`, `created_ts`) duplicate its key fields for filtering.

| Field | Meaning |
|---|---|
| `alert_id` | Unique, never reused. The idempotency key. |
| `business_date`, `summary` | Date raised for; the rule's name |
| `delivery.id`, `delivery.revision` | Which delivery it belongs to |
| `rule.code`, `rule.name`, `rule.version` | The rule that fired and its version at that time |
| `account.id`, `account.product_type` | The account (`CARD`, `LOAN`, `DEPOSIT`) |
| `customer.id` | The account's primary customer: the party the alert is about |
| `customer.name`, `.type`, `.country`, `.state` | **Snapshot taken when the alert was created**; later customer changes do not alter it |
| `evidence` | Rule-specific facts: totals, counts, window, thresholds, ratios, new values. Always holds the true totals |
| `transactions` | The transactions behind the alert (id, posting date, time, type, direction, amount, currency, channel, counterparty name and country). **Capped at 200 per alert**; use `evidence` for the full totals |

Example:
```json
{
  "alert_id": 4711, "business_date": "2026-10-01", "summary": "Large cash activity in one day",
  "delivery": {"id": 12, "revision": 1},
  "rule": {"code": "LARGE_CASH_DAILY", "name": "Large cash activity in one day", "version": 1},
  "account": {"id": "A1", "product_type": "DEPOSIT"},
  "customer": {"id": "C1", "name": "Ada Lovelace", "type": "INDIVIDUAL", "country": "US", "state": "NY"},
  "evidence": {"window_days": 1, "txn_count": 2, "total": 11000.0000, "min_sum": 10000.01, "min_count": 1},
  "transactions": [
    {"transaction_id": "T1", "posting_date": "2026-09-30", "txn_ts": "2026-09-30T10:15:00", "txn_type": "CASH_DEPOSIT",
     "direction": "CREDIT", "amount": 6000.0000, "currency": "USD", "channel": null,
     "counterparty_name": null, "counterparty_country": null}
  ]
}
```

## 7. Reconciliation

### 7.1 Three levels

| Level | Question | Who | How |
|---|---|---|---|
| **1. Delivery** | Did the consumer receive exactly the alerts the engine published? | Both, automatically | Count + checksum compared by `confirm_delivery` |
| **2. Alert** | Where does each alert stand? | Both | `v_alert_reconciliation` and `alert_rejection`: created, acknowledged, pending, rejected, withdrawn |
| **3. Daily** | Is every business date accounted for, with nothing stale? | Operations, daily | Reconciliation view for the last days plus the `health` job |

The consumer should keep its **own** record per delivery (id, count, checksum, time confirmed) so that either side can show the other its figures.

### 7.2 What the engine shows: `aml.v_alert_reconciliation`
One row per published delivery:

| Column | Meaning |
|---|---|
| `expected_count`, `checksum` | Control totals written at publication |
| `created_alerts` | Alerts actually belonging to the delivery (should equal `expected_count`) |
| `acknowledged_alerts` / `pending_alerts` | Handed off / still waiting |
| `rejected_unresolved` | Alerts the consumer rejected that are not yet resolved |
| `withdrawn_alerts` | Alerts of this delivery later withdrawn |
| `received_count`, `received_checksum` | What the consumer reported |
| `hours_unconfirmed` | Hours since publication while not confirmed |
| `confirmation_channel`, `confirmation_reference` | `DB` (function), `OPERATOR` (out-of-band job) or `FILE` (confirmation file), plus the ticket or message reference |

```sql
SELECT * FROM aml.v_alert_reconciliation
WHERE business_date >= CURRENT_DATE - 7
ORDER BY business_date DESC, revision DESC;
```

### 7.3 A healthy day looks like
`status = CONFIRMED`, `expected_count = created_alerts = received_count`, `checksum = received_checksum`, `pending_alerts = 0`, `rejected_unresolved = 0`.

### 7.4 When it does not reconcile

| Situation | What the numbers show | Action |
|---|---|---|
| Consumer missed alerts | `received_count` below `expected_count` | Read the delivery again (still visible), ingest the rest, confirm again |
| Consumer loaded some twice | `received_count` above expected | Fix the duplicate; idempotent ingest keyed on `alert_id` prevents it |
| Same count, different checksum | Different alerts than published | Compare id lists with the engine team |
| Consumer rejected alerts | Count below expected, rejections recorded | Engine team fixes the cause (usually missing or wrong data), tells the consumer to re-read, consumer confirms with the full count; the engine team then marks the rejection resolved |
| Delivery unconfirmed for too long | `hours_unconfirmed` high; health reports `DELIVERY_NOT_CONFIRMED` (default 12 hours) | Operations contacts the case management owner |
| Delivery stuck `MISMATCH` | Health reports `DELIVERY_MISMATCH` | Both sides compare the figures above |

## 8. Confirmation when the consumer cannot call the function

If the consumer can only read, confirmation can arrive another way. The comparison is exactly the same; the result records how and why.

**One delivery, by an operator** (numbers received by message, ticket or e-mail):
```
FISRE_JOB=confirm-delivery  FISRE_DELIVERY_ID=12  FISRE_RECEIVED_COUNT=340
FISRE_RECEIVED_CHECKSUM=<hex>  FISRE_CONFIRM_REFERENCE=TICKET-4521   java -jar fisre-engine.jar
```
**Many, from a file** the consumer drops (one line per delivery; the header is optional; the reference column is optional and defaults to the file name):
```
delivery_id,received_count,received_checksum,reference
12,340,9b2c...e1,MSG-77
13,12,41af...09,MSG-78
```
```
FISRE_JOB=import-confirmations  FISRE_CONFIRM_FILE=/data/case-mgmt-confirmations.csv   java -jar fisre-engine.jar
```
Every line is applied and reported; a bad line (wrong numbers, unknown delivery, malformed) does not stop the others, but the job exits with an error so it is noticed. A reference is **required** for audit: it is stored with the confirmation (`OPERATOR` or `FILE`). A mismatch is not recorded as a confirmation.

## 9. Corrections after publication

A published delivery is frozen. If data is corrected and the date is processed again:
- **New alerts** that were not raised before become **revision 2** of that date (a new delivery, with its own control totals, to be read and confirmed like any other).
- **Alerts that would no longer be raised** are reported as `WITHDRAWN` events; the alerts stay in the delivery as handed over.
- **Nothing changes** if the re-run produces nothing new: no new revision, no events.
- Evidence of an existing alert is not extended: alerts are what the consumer received.

```mermaid
sequenceDiagram
  participant Engine
  participant CM as Case management
  Engine->>CM: delivery 12 (rev 1, 340 alerts) READY
  CM->>Engine: confirm 340 -> CONFIRMED
  Note over Engine: corrected data arrives, date re-run
  Engine->>CM: delivery 15 (rev 2, 3 new alerts) READY
  Engine->>CM: event: alert 4711 WITHDRAWN
  CM->>Engine: confirm delivery 15 -> CONFIRMED
```

## 10. Failure and edge cases

| Case | What happens | Consumer action |
|---|---|---|
| A rule fails during the night | The delivery stays `OPEN`; nothing is visible; health reports `RULE_FAILED` | Wait. The engine team fixes the rule and re-runs; the same delivery is then published |
| The nightly job has not finished | No delivery for the date | Keep polling; operations see `NIGHTLY_INCOMPLETE` / `DELIVERY_NOT_PUBLISHED` |
| A feed arrives truncated | The engine rejects the batch (volume check `VOL-001`); nothing is published for that date until the feed is fixed | Wait |
| A day has no alerts | `READY` with count 0 and the empty-string checksum | Confirm with count 0 and that checksum |
| Consumer is down for days | Deliveries accumulate, each `READY` | Ingest oldest first, confirm each |
| Consumer crashed mid-ingest | Nothing was confirmed; alerts remain visible | Restart; idempotent ingest makes re-reading safe |
| The same delivery is confirmed twice | Same numbers: `CONFIRMED` again; different numbers: error | None |
| Notification missed | None needed | The poll finds it |
| Engine restored from backup | Deliveries after the restore point may be gone or rebuilt | Operations compares `v_alert_reconciliation` with the consumer's own record and agrees what is re-sent |

## 11. Operations and ownership

| Event | Detected by | Owner |
|---|---|---|
| Delivery not published by the agreed time | `health` (`DELIVERY_NOT_PUBLISHED`), run after the nightly job | Engine operations |
| Delivery unconfirmed beyond the limit | `health` (`DELIVERY_NOT_CONFIRMED`, default 12 h) | Engine operations contacts case management |
| Mismatch | `health` (`DELIVERY_MISMATCH`) | Both sides, led by the case management owner |
| Rejected alerts | `health` warning (`ALERT_REJECTED`) | Engine team resolves the cause and the rejection |
| Withdrawn events | `v_alert_events` | Case management reviews affected alerts |

The health job exits non-zero on critical findings, so a scheduler can alert on it. Dashboards can use `v_alert_reconciliation`, `v_ops_alert_backlog` and `v_ops_failures`.

## 12. Security and access

The consumer's database user needs **only**:
- `SELECT` on `aml.v_alert_delivery`, `aml.v_alert_export`, `aml.v_alert_events`, `aml.v_alert_reconciliation`;
- `EXECUTE` on `aml.confirm_delivery`, `aml.reject_alerts`, `aml.compute_alert_checksum` (and optionally `aml.ack_alerts`).

It gets no access to any engine table. The script is `ops/db/postgresql/case_mgmt_grants.sql`. The payload contains customer names and transaction details: access to the views must be limited accordingly and every call is attributable (the database user is recorded on confirmations and rejections). Once an alert is handed off it cannot be changed or deleted by the engine (database trigger); a purge after the retention period is a documented, change-controlled procedure. If the consumer can only read, section 8 covers confirmation without any write access.

## 13. Non-functional notes

- **Volume:** 300 to 5,000 alerts a day; at most about 200 transactions in each alert's list, so a payload is small. Page size 500 to 1,000.
- **Idempotency:** every read is repeatable; every write the consumer makes (confirm, reject) is safe to repeat.
- **Ordering:** read deliveries oldest first by business date, then revision.
- **Retention:** how long the engine keeps delivered alerts is **Open** (compliance); the structures support purge under change control.
- **Time zone:** timestamps are database local time; the business date is a calendar date.

## 14. Open decisions

| # | Decision | Suggested default |
|---|---|---|
| 1 | Which availability signal does case management use: poll only, or poll plus notification, or an out-of-band message? | Poll every 10 minutes from 1 am, plus notification if a long-lived connection is acceptable |
| 2 | Confirmation method: function call, out-of-band job, or file drop? | Function call if write access is acceptable; file drop otherwise |
| 3 | "Ready by" time for deliveries (and escalation if missed) | To be set after measuring on production hardware |
| 4 | Time allowed before an unconfirmed delivery is escalated | 12 hours (configurable) |
| 5 | Who resolves a rejection and how it is marked resolved | Engine team marks it resolved when the cause is fixed and the consumer re-reads |
| 6 | Treatment of `WITHDRAWN` alerts in case management (flag, close, review) | Flag for analyst review; never delete the case automatically |
| 7 | How long delivered alerts are kept | Per compliance policy, at least the SAR retention period |
| 8 | Whether the consumer needs a second consumer-side copy of reconciliation figures sent back to operations (for example a daily e-mail) | Consumer keeps its own record; operations compares on request |

## 15. Acceptance checklist for the case management side

Case management should demonstrate, against a test environment fed by the engine:

1. Finds a `READY` delivery by polling (and, if used, by notification).
2. Reads all alerts of a delivery in pages and stores each exactly once, keyed by `alert_id`.
3. Computes the same checksum as `compute_alert_checksum` for a delivery and gets `CONFIRMED`.
4. Re-reads a delivery after a simulated crash without duplicating anything.
5. Confirms a zero-alert delivery (count 0, empty-string checksum).
6. Deliberately sends a wrong count and sees `MISMATCH`, then recovers with the right one.
7. Rejects an alert with a reason and sees it in the reconciliation view.
8. Processes a revision 2 delivery and a `WITHDRAWN` event.
9. (If using out-of-band confirmation) Delivers a confirmation file and sees `FILE` and the reference recorded.
10. Produces its own per-delivery reconciliation record that matches `v_alert_reconciliation`.

## 16. Glossary

**Alert**: a detection result for one account, one rule, one business date. **Business date**: the date of the nightly batch. **Control totals**: count, per-rule counts and checksum written at publication. **Delivery**: the alerts of one business date, published together. **Idempotent**: safe to repeat with the same result. **Revision**: a further delivery for a date after a correction. **Withdrawn**: an alert that a corrected re-run no longer produces.

## 17. References

- Contract details and exact column lists: `specs/data-contract/alert-export.md`
- Requirements: `specs/requirements/delivery.md`, `handoff.md`
- Design rationale: ADR-0006, ADR-0008 in `specs/adr/`
- Operations: `ops/RUNBOOK.md`; access script: `ops/db/postgresql/case_mgmt_grants.sql`
