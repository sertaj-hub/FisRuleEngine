# Rule spec and config reference

Spec file (`specs/rules/<CODE>.yml`):

```yaml
code: LARGE_CASH_DAILY          # unique, stable id; alerts carry it
name: Large cash activity in one day
description: ...
template: AGGREGATE             # AGGREGATE | FLOW_THROUGH | SEQUENCE | DORMANT_REACTIVATION
status: ACTIVE                  # DRAFT | ACTIVE | RETIRED
suppress_days: 0                # skip a hit if the account alerted on this rule in the previous N days
config: { ... }                 # per template, below
tests: [ ... ]                  # scenarios, see below
```

Common to every template: `product_types` (optional list of CARD, LOAN, DEPOSIT; omitted means all products).

**Filter** (a transaction filter; every field optional, all must match): `txn_types` (list), `cash` (true/false, from `ref_txn_type.is_cash`),
`direction` (CREDIT, DEBIT or ANY), `channels` (list), `min_amount` (inclusive), `max_amount` (exclusive).
Direction is from the account's point of view: CREDIT adds funds to the account or reduces what a card or loan owes.

Windows are in posting days and end on the business date: an N-day window is `[date - (N-1), date]`.

| Template | Keys | Meaning |
|---|---|---|
| `AGGREGATE` | `window_days`, `filter`, `min_count` (default 1), `min_sum` (default 0) | Matching transactions in the window number at least `min_count` and total at least `min_sum`. Needs `min_count` > 1 or `min_sum` > 0. Covers large cash, structuring (amount band in `filter`), velocity. |
| `FLOW_THROUGH` | `window_days`, `in`, `out` (filters), `min_in`, `min_out_pct` | Inflow (`in`) is at least `min_in` and outflow (`out`) is at least `min_out_pct` percent of it. |
| `SEQUENCE` | `first`, `then` (filters), `within_days`, `first_min_total`, `then_min_total`, `then_min_pct_of_first` | On the business date the `then` events total at least `then_min_total`; `first` events in the previous `within_days` days, each earlier than the latest `then` event, total at least `first_min_total`, and `then` is at least `then_min_pct_of_first` percent of `first`. Covers credit balance refund and loan early payoff. |
| `DORMANT_REACTIVATION` | `dormant_days`, `filter`, `min_total` | The account is older than `dormant_days`, had no transactions in the previous `dormant_days` days, and matching transactions on the business date total at least `min_total`. |

Test scenarios (run by the build, REQ-RULE-009):

```yaml
tests:
  - name: three cash deposits under the limit
    business_date: 2026-09-30
    accounts: [{id: A1, product: DEPOSIT}]        # optional: customer, open_date (default 2020-01-01)
    txns:
      - {id: T1, account: A1, type: CASH_DEPOSIT, direction: CREDIT, amount: 4000, date: 2026-09-30, time: "10:00"}
    expect_alerts: [A1]                            # account ids; [] means no alert
```
