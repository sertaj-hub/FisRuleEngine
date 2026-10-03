# Alert handoff to case management

Case management is a separate product that **reads from the database** (no files, no queue). The contract is one view and one function; details in `data-contract/alert-export.md` and ADR-0006.

| ID | Requirement | Status |
|---|---|---|
| REQ-HND-001 | `aml.v_alert_export` lists every alert not yet handed off, one row per alert, with one self-contained JSON `payload`: rule (code, name, version), business date, account, the primary customer snapshot, the evidence and the linked transactions with their details. | Implemented |
| REQ-HND-002 | `aml.ack_alerts(ids)` marks the given alerts as handed off and returns how many were newly marked; it is idempotent, ignores unknown ids, and acknowledged alerts disappear from the view and are never changed by re-runs. | Implemented |
| REQ-HND-003 | The view's columns are a stable, documented contract (`alert_id`, `rule_code`, `business_date`, `account_id`, `customer_id`, `product_type`, `created_ts`, `payload`); a test fails if they change. | Implemented |
| REQ-HND-004 | The customer snapshot is taken from master when the alert is created, so later customer changes do not alter an alert's content. | Implemented |
