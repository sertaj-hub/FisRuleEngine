# Rule spec and config reference

Spec file (`specs/rules/<CODE>.yml`):

```yaml
code: LARGE_CASH_DAILY          # unique, stable id; alerts carry it
name: Large cash activity in one day
description: ...
template: AGGREGATE             # AGGREGATE | FLOW_THROUGH | SEQUENCE | DORMANT_REACTIVATION | BASELINE_DEVIATION | NEW_ATTRIBUTE
status: ACTIVE                  # DRAFT | ACTIVE | RETIRED
suppress_days: 0                # skip a hit if the account alerted on this rule in the previous N days
config: { ... }                 # per template, below
tests: [ ... ]                  # scenarios, see below
```

Common to every template: `product_types` (optional list of CARD, LOAN, DEPOSIT; omitted means all products).

**Filter** (a transaction filter; every field optional, all must match): `txn_types` (list), `cash` (true/false, from `ref_txn_type.is_cash`),
`direction` (CREDIT, DEBIT or ANY), `channels` (list), `min_amount` (inclusive), `max_amount` (exclusive).
Direction is from the account's point of view: CREDIT adds funds to the account or reduces what a card or loan owes.

Windows are in posting days and end on the **as-of day** (the posting day, which is the batch's business date minus `posting-offset-days`): an N-day window is `[as_of - (N-1), as_of]`.
A rule only evaluates accounts that had a matching transaction on the as-of day (new activity); it then looks back over the window.

| Template | Keys | Meaning |
|---|---|---|
| `AGGREGATE` | `window_days`, `filter`, `min_count` (default 1), `min_sum` (default 0) | Matching transactions in the window number at least `min_count` and total at least `min_sum`. Needs `min_count` > 1 or `min_sum` > 0. Covers large cash, structuring (amount band in `filter`), velocity. |
| `FLOW_THROUGH` | `window_days`, `in`, `out` (filters), `min_in`, `min_out_pct` | Inflow (`in`) is at least `min_in` and outflow (`out`) is at least `min_out_pct` percent of it. |
| `SEQUENCE` | `first`, `then` (filters), `within_days`, `first_min_total`, `then_min_total`, `then_min_pct_of_first` | On the business date the `then` events total at least `then_min_total`; `first` events in the previous `within_days` days, each earlier than the latest `then` event, total at least `first_min_total`, and `then` is at least `then_min_pct_of_first` percent of `first`. Covers credit balance refund and loan early payoff. |
| `DORMANT_REACTIVATION` | `dormant_days`, `filter`, `min_total` | The account is older than `dormant_days`, had no transactions in the previous `dormant_days` days, and matching transactions on the business date total at least `min_total`. |
| `BASELINE_DEVIATION` | `baseline_days`, `filter`, `metric` (SUM or COUNT), `multiplier`, `min_value` (default 0), `min_active_days` (default 1) | The as-of day's matching `metric` is at least `multiplier` times the account's own average per calendar day over the previous `baseline_days` days (the baseline window ends the day before the as-of day), and at least `min_value`; the account was active (matching transactions) on at least `min_active_days` baseline days. Accounts with no baseline never alert. |
| `NEW_ATTRIBUTE` | `attribute` (counterparty_country, counterparty_account, counterparty_name, merchant_category_code or channel), `baseline_days`, `filter`, `min_active_days` (default 1) | Matching transactions on the as-of day carry a non-empty `attribute` value that the account did not use in the previous `baseline_days` days; the account had any transactions on at least `min_active_days` baseline days. |

Baseline templates read the previous `baseline_days` of history, so those posting days must be loaded (the planned start is one month).

Test scenarios (run by the build, REQ-RULE-009):

```yaml
tests:
  - name: three cash deposits under the limit
    as_of: 2026-09-30                              # the posting day the windows end on
    accounts: [{id: A1, product: DEPOSIT}]        # optional: customer, open_date (default 2020-01-01)
    txns:
      - {id: T1, account: A1, type: CASH_DEPOSIT, direction: CREDIT, amount: 4000, date: 2026-09-30, time: "10:00"}   # optional: counterparty_country
    expect_alerts: [A1]                            # account ids; [] means no alert
```

## ML_SCORE

Keys: `model` (name, default `account_anomaly`), `min_score` (0 to 1, required), optional `product_types`. Reads aml.ml_score of the ACTIVE model for the as-of day; fails the rule if there are none. See `ml-scores.md` and ADR-0009.
