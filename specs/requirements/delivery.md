# Alert delivery to case management (outbox with reconciliation)

Alerts reach case management in **one delivery per business date**, published after detection finishes, and the consumer reconciles it with control totals. Design: ADR-0008. Contract: `data-contract/alert-export.md`.

A delivery has a revision (1, 2, ...) and a status: `OPEN` (being built, invisible) → `READY` (published, control totals written) → `CONFIRMED` (the consumer's numbers matched) or `MISMATCH` (they did not).

| ID | Requirement | Status |
|---|---|---|
| REQ-DLV-001 | A detection run puts the business date's new alerts in a delivery that stays `OPEN` (invisible) while rules run. When every rule has succeeded it becomes `READY` with control totals: alert count, count per rule and a checksum. A date with no alerts is still published, with count 0. | Implemented |
| REQ-DLV-002 | `aml.v_alert_export` shows only unsent alerts of `READY` or `MISMATCH` deliveries, so a partial day is never visible. | Implemented |
| REQ-DLV-003 | `aml.v_alert_delivery` lists published deliveries with status and control totals. The checksum is the SHA-256 (hex) of the delivery's alert ids sorted ascending and joined with commas; `aml.compute_alert_checksum(ids)` computes it. | Implemented |
| REQ-DLV-004 | `aml.confirm_delivery(delivery_id, received_count, received_checksum)` compares the consumer's numbers with the control totals. A match sets `CONFIRMED` and marks all the delivery's alerts handed off; a mismatch sets `MISMATCH`, records what was received and leaves the alerts visible. Repeating a matching confirmation is harmless; an `OPEN` delivery is refused. | Implemented |
| REQ-DLV-005 | `aml.reject_alerts(alert_ids, reason)` records that the consumer could not ingest alerts, with the reason and the database user; unknown ids are ignored. Nothing is deleted. | Implemented |
| REQ-DLV-006 | A re-run never changes the alerts of a delivery that is `READY` or later. Hits that were not alerted before form the next revision; a re-run that adds nothing creates no revision. | Implemented |
| REQ-DLV-007 | When a re-run no longer produces an alert that was already published, a `WITHDRAWN` event is added to `aml.v_alert_events` (monotonic `event_id`); an alert that still hits gets none, and the alert itself is not changed. | Implemented |
| REQ-DLV-008 | If any rule failed, the delivery stays `OPEN` and nothing of that run is visible; the next successful run for that date replaces the OPEN delivery's alerts and publishes it. | Implemented |
| REQ-DLV-009 | `aml.v_alert_reconciliation` shows per delivery: expected and created alerts, acknowledged, pending, unresolved rejections, withdrawn, the received numbers and the hours unconfirmed. | Implemented |
| REQ-HLT-004 | `health` reports `DELIVERY_MISMATCH` (CRITICAL), a `READY` delivery unconfirmed for more than `health-confirm-hours` (CRITICAL), no published delivery for the given business date (CRITICAL) and unresolved rejections (WARN). | Implemented |
| REQ-DLV-010 | Publishing a delivery sends a PostgreSQL notification on channel `aml_delivery_ready` (JSON: delivery id, business date, revision, alert count), delivered when the publishing transaction commits. It is only a hint: polling `aml.v_alert_delivery` is always sufficient, and a delivery that is not published (a no-op revision) sends none. | Implemented |
| REQ-DLV-011 | Confirmation can also arrive out of band: the `confirm-delivery` job applies the same comparison as `aml.confirm_delivery` to numbers received another way, requires a reference (ticket, e-mail id, message id) and records the channel `OPERATOR` and the reference on the delivery. | Implemented |
| REQ-DLV-012 | The `import-confirmations` job reads a CSV file of confirmations (`delivery_id,received_count,received_checksum[,reference]`, optional header), applies each line like REQ-DLV-011 with channel `FILE` and the file name as the default reference, reports every line's outcome, and fails the job if any line mismatches or errors; one bad line does not stop the others. | Implemented |
