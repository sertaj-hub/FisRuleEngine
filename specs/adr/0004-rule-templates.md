# ADR-0004: Generic rule templates, configured per rule

Status: accepted

Rules are not hard-coded per product. A small set of generic, tested **templates** (AGGREGATE, FLOW_THROUGH, SEQUENCE, DORMANT_REACTIVATION) each turn a JSON config
(scope filters on product and transaction attributes, thresholds, windows) into one set-based SQL statement. A new monitoring idea is usually a new configuration, not new code:
large cash, structuring, velocity are all `AGGREGATE`; credit balance refund and loan early payoff are `SEQUENCE`.

- Source of truth: `specs/rules/*.yml` with test scenarios; loaded into `aml.rule` (versioned). A UI will later edit the same table.
- No per-rule severity; an alert is a hit.
- Alerts: one per rule, account and business date, addressed to the account's primary customer, with JSON evidence and linked transactions.
- Re-runs replace unsent alerts; handed-off alerts are never touched. `suppress_days` stops a rolling window re-alerting every day.
- Config values are always bound parameters, never concatenated into SQL.

Limits: `FLOW_THROUGH` compares window totals (it does not require each credit to precede its debit). Credit balance refund is approximated from payments and refunds, because balances are not in the data contract yet.
