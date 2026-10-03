# AML Case Management: Technical Specification for the Consumer of Rule Engine Alerts

| | |
|---|---|
| **Audience** | FIS case management team (engineering, product, QA) and Compliance stakeholders |
| **Producer** | FIS Rule Engine (batch AML transaction monitoring; cards, loans, deposits; US BSA) |
| **Version** | 1.0 draft for review |
| **Companion documents** | `case-management-alert-intake.md` (how to read and reconcile), `alert-payload.schema.json` (machine-readable payload), `samples/` (real engine output) |

**How to read this document.** Part A says exactly what the rule engine gives you. Part B says what we ask you to build: how to turn alerts into **one case per party** for compliance investigation. Part C describes how a case that ends as a true positive becomes a SAR. Part D covers non-functional needs, feedback to the engine and acceptance tests.

**Status of requirements.** Statements marked **MUST / SHOULD / MAY** are requirements on the case management product (IDs `CMR-…`). Anything about the SAR regulation is a *summary for engineers*; **Compliance owns the interpretation** and must confirm Part C before it is built. Items needing a decision are collected in section 14.

---

## 1. Purpose and scope

The rule engine reads the bank's transactions every night and raises **alerts**: statements of the form *"on this account, this rule found this activity, and here is the evidence"*. An alert is **not** a case and **not** a conclusion. It is a lead.

Case management receives alerts, groups them by the party they concern, lets compliance analysts investigate, records a decision, and, for true positives, supports filing a Suspicious Activity Report (SAR) with FinCEN.

| In scope for case management | Out of scope for the rule engine (not provided) |
|---|---|
| Ingesting delivered alerts, reconciling, rejecting what cannot be ingested | Sanctions / watchlist screening |
| Party-centric case creation and alert grouping | KYC risk scoring, TIN, address, date of birth |
| Investigation workflow, dispositions, four-eyes approval | Real-time detection |
| SAR preparation, approval, filing support, deadlines | Alert severity or score (see 5.4) |
| Feeding dispositions back for rule tuning | Case management itself |

## 2. Context

```
 Bank systems --> stg --> mst --> [ Rule Engine ] --> aml.* views/functions --> [ Case Management ] --> SAR (FinCEN)
 (nightly batch)                      detects                  the data interface              investigates, files
```

Integration is by **database contract**: case management reads views in schema `aml` and calls two functions. There is no message broker and no REST API. Names and behavior of those objects are the contract; everything else in the engine databases is private and may change.

## 3. Interface summary (what you read, what you call)

| Object | Kind | Use |
|---|---|---|
| `aml.v_alert_delivery` | view | One row per *published* delivery: id, business date, revision, status, alert count, per-rule counts, checksum |
| `aml.v_alert_export` | view | One row per alert of an unconfirmed published delivery: `alert_id, delivery_id, rule_code, business_date, account_id, customer_id, product_type, created_ts, payload` (JSON) |
| `aml.v_alert_events` | view | Corrections after publication, e.g. `WITHDRAWN` |
| `aml.v_alert_reconciliation` | view | Both sides' control totals per delivery |
| `aml.v_alert_rejections`, `aml.v_alert_rejection_items` | views | Rejections you reported and their resolution |
| `aml.confirm_delivery(delivery_id, count, checksum)` | function | Confirm that you hold exactly what was published |
| `aml.reject_alerts(alert_ids[], reason)` | function | Report alerts you could not ingest, with a mandatory reason |
| `aml.compute_alert_checksum(alert_ids[])` | function | Same checksum algorithm the engine uses |
| Notification channel `aml_delivery_ready` | PostgreSQL `NOTIFY` | "A delivery was published." Fast signal, **not durable** |
| Notification channel `aml_alert_rejected` | PostgreSQL `NOTIFY` | Engine-side signal for operations |

Step-by-step reading, paging SQL, confirmation, out-of-band confirmation and the rejection/remediation process are in **`case-management-alert-intake.md`** and are normative for the *mechanics*. This document is normative for the *meaning* of the data and for what the case management product must do with it.

### 3.1 Delivery model in one paragraph

All alerts for one **business date** are published together as one **delivery** (revision 1). Nothing is visible while the delivery is being built or if any rule failed. After publication the delivery is **frozen**. If corrected data is re-processed, new alerts appear as the next **revision** (a new delivery for the same business date) and alerts that would no longer be raised are announced as `WITHDRAWN` events. A delivery's control totals are `alert_count`, `rule_counts` and `checksum` (SHA-256 hex of the alert ids sorted ascending, joined by commas; an empty delivery uses the SHA-256 of the empty string). Sample: `samples/deliveries.json`.

---

# PART A: WHAT THE ENGINE GIVES YOU

## 4. The alert

### 4.1 Grain

**One alert per rule, per account, per business date**, addressed to **the primary customer of that account**. The same account can raise several alerts on one date (one per rule that fired). The same customer can have alerts on several accounts and products.

Consequences you must design for:

- Two alerts on the same date can **share transactions** (a cash deposit can be part of both a "large cash" and a "structuring" alert). Never add alert totals together to get "activity amount" (see CMR-CASE-07).
- Alerts never carry a case, status or disposition. Those are yours.
- An alert is **immutable** once handed over. It is a snapshot.

### 4.2 Payload: field-by-field

The payload is JSON (`aml.v_alert_export.payload`); the schema is `alert-payload.schema.json`. Real examples are in `samples/`.

| Path | Type | Null? | Meaning and notes |
|---|---|---|---|
| `alert_id` | integer | no | Globally unique, never reused. **Your idempotency key.** Same as the view column |
| `business_date` | date | no | Date of the batch run. Activity is mostly from the previous day(s): see `transactions[].posting_date` |
| `summary` | string | no | One-line human title (the rule's name) |
| `delivery.id`, `delivery.revision` | integer | no | Delivery this alert belongs to; revision 1 is the first for that business date |
| `rule.code` | string | no | Stable rule identifier, see the rule catalog (section 6) |
| `rule.name` | string | no | Display name |
| `rule.version` | integer | no | Version of the rule definition that produced the alert. Keep it for audit and tuning |
| `account.id` | string | no | The account the activity occurred on |
| `account.product_type` | enum | no | `CARD`, `LOAN`, `DEPOSIT` |
| `customer.id` | string | no | **Primary customer of the account.** Your **party key** |
| `customer.name` | string | yes | Name **as at alert creation** (snapshot) |
| `customer.type` | string | yes | `INDIVIDUAL` or `ORGANIZATION` (bank data; treat other values as opaque) |
| `customer.country`, `customer.state` | string | yes | Country / US state code of the customer record |
| `evidence` | object | no | Rule-specific facts that triggered the alert. Keys per rule in section 6. Numbers are JSON numbers; dates are ISO strings |
| `transactions[]` | array | no | The transactions behind the alert. **Capped at 200**; see `evidence.txn_count` for the true count |
| `transactions[].transaction_id` | string | no | Bank transaction id; unique in the bank's system |
| `transactions[].posting_date` | date | no | Posting day |
| `transactions[].txn_ts` | timestamp | yes | Local timestamp without zone as received from the bank |
| `transactions[].txn_type` | enum | no | One of the values in Appendix B |
| `transactions[].direction` | enum | no | `CREDIT` (money into the account) or `DEBIT` (out). `amount` is always positive |
| `transactions[].amount` | decimal | no | In `currency`; the engine does no currency conversion |
| `transactions[].currency` | string | yes | ISO 4217 |
| `transactions[].channel` | string | yes | Origination channel when the bank supplies it |
| `transactions[].counterparty_name` / `_country` | string | yes | Other party of the transaction, when the bank supplies it |

### 4.3 Guarantees (invariants you may rely on)

1. `alert_id` is unique and stable. Reading an alert twice returns the same content.
2. The payload is **self-contained**: you never need to query any other engine table to ingest it.
3. `customer.id` and `account.id` are the bank's identifiers, equal to those in the bank's customer and account systems.
4. If `evidence.txn_count` exceeds `transactions` length, the list is a capped sample; the evidence totals are still for the full set.
5. A delivery is only visible when complete; counts and checksum describe exactly what you can read.
6. Alerts are never edited or deleted after handover. Corrections are new alerts (new revision) or `WITHDRAWN` events.

### 4.4 What the alert does NOT contain

| Not provided | Therefore case management must |
|---|---|
| Severity, score, priority | Prioritise cases itself (section 9.4). Product decision: the engine deliberately has no per-rule severity |
| TIN/SSN, address, date of birth, occupation, phone | Pull from the bank's customer/KYC system at investigation and SAR time (section 12.2) |
| Joint account owners / other parties on the account | Obtain from the bank's account system. Only the primary customer is addressed |
| Balances, credit limits, account status | Obtain from the bank's systems when investigating |
| Narrative or suspected-activity category | Analyst-authored (Part C) |
| Prior alerts / history of the customer | Your own case history |

## 5. Rule catalog

All rules are generic *templates* configured with parameters, not product-specific code. Parameters below are the current configuration; they will change over time, which is why `rule.version` is carried in every alert.

| Rule code | Product | What it detects | Template | Evidence keys |
|---|---|---|---|---|
| `LARGE_CASH_DAILY` | Deposit | Cash in or out over $10,000 in one day (CTR-level) | AGGREGATE | `window_days, txn_count, total, min_count, min_sum` |
| `STRUCTURING_CASH_DEPOSITS` | Deposit | Several cash deposits, each below $10,000, totalling more than $10,000 within 5 days | AGGREGATE | `window_days, txn_count, total, min_count, min_sum` |
| `CARD_CASH_ADVANCE_VELOCITY` | Card | 3 or more cash advances totalling at least $1,500 within 7 days | AGGREGATE | same as above |
| `RAPID_MOVEMENT_OF_FUNDS` | Deposit | At least $10,000 in and at least 80% of it out again within 3 days | FLOW_THROUGH | `window_days, total_in, total_out, out_pct, min_in, min_out_pct` |
| `CREDIT_BALANCE_REFUND` | Card | Refund of a credit balance after payments (overpay then refund) | SEQUENCE | `within_days, first_total, then_total, then_pct_of_first` |
| `LOAN_EARLY_PAYOFF` | Loan | Loan paid off within 90 days of disbursement | SEQUENCE | same as above |
| `DORMANT_REACTIVATION` | Deposit | No transactions for 180 days, then $5,000 or more of activity | DORMANT_REACTIVATION | `dormant_days, total, last_activity` |
| `DEBIT_SPIKE_VS_BASELINE` | Deposit | Debit activity far above the account's own 30-day baseline | BASELINE_DEVIATION | `metric, today, baseline_daily_average, ratio, multiplier, baseline_days, baseline_active_days` |
| `NEW_COUNTERPARTY_COUNTRY` | Deposit | First transactions to a counterparty country not seen in the baseline period | NEW_ATTRIBUTE | `attribute, new_values[], txn_count, total, baseline_days` |

Notes: `out_pct` and `then_pct_of_first` are percentages (80.0 = 80%). In `BASELINE_DEVIATION`, `ratio = today / baseline_daily_average`. The authoritative, current definitions are the YAML files in `specs/rules/` of the engine repository (with test scenarios); request read access if you want the exact parameters.

Samples for every rule: `samples/alert-<rule>.json`. **Use them as contract test fixtures** (section 17).

---

# PART B: WHAT CASE MANAGEMENT SHOULD BUILD

## 6. Requirements: ingestion and reconciliation

| ID | Req | Level |
|---|---|---|
| CMR-ING-01 | Detect new deliveries from the `aml_delivery_ready` notification **and** a safety-net poll of `v_alert_delivery` (the notification is not durable and may be lost on reconnect) | MUST |
| CMR-ING-02 | Read each delivery in pages ordered by `alert_id`; resume after restart without duplication | MUST |
| CMR-ING-03 | Ingest **idempotently** keyed on `alert_id`; re-delivery of an alert must not create a second alert record or case | MUST |
| CMR-ING-04 | Store the full payload as received (immutable raw copy) plus parsed fields, for audit | MUST |
| CMR-ING-05 | After ingesting a delivery, compute count and checksum **from your own stored alert ids** and call `confirm_delivery`. A mismatch must not be forced | MUST |
| CMR-ING-06 | An alert that cannot be ingested (e.g. unknown customer, invalid data) must be reported with `reject_alerts` and a specific reason; the rest of the delivery continues. Never drop silently | MUST |
| CMR-ING-07 | Keep your own per-delivery record: id, revision, count, checksum, times read and confirmed, rejected ids | MUST |
| CMR-ING-08 | Process `v_alert_events` in `event_id` order, remembering the last processed id | MUST |
| CMR-ING-09 | Treat `delivery.revision > 1` as additional alerts for a date already processed. It is a normal case, not an error | MUST |
| CMR-ING-10 | Tolerate unknown extra JSON keys and unknown `evidence` keys (forward compatible); reject only when a required key is missing or malformed | MUST |
| CMR-ING-11 | Do not require the engine to be ready at a fixed time. No "ready by" time has been agreed: react when published | SHOULD |
| CMR-ING-12 | Alert an operator if a business day has no delivery after an agreed time (owner and time are open decisions, section 14) | SHOULD |

Detailed procedure: `case-management-alert-intake.md` sections 5 to 9.

## 7. Party-centric case creation

### 7.1 Principle

> **The unit of investigation is the party (customer), not the alert.** All open alerts for one party, across accounts, products and days, belong in **one open case**.

A compliance analyst looking at Daniel Okafor's structuring on his checking account and his card cash advances needs to see both at once, because together they tell the story. Opening three cases would triple the work and hide the pattern.

### 7.2 Party key

Party = `customer.id`. The engine addresses every alert to the account's primary customer, so every alert has exactly one party. `customer.name/type/country/state` are a snapshot; **your party master (from the bank's customer system) is the source of truth**. If `customer.name` differs from your master, keep both and do not reject.

### 7.3 Grouping algorithm (normative)

For each ingested alert (processed in `alert_id` order within a delivery):

```
find open case C for party = alert.customer.id
if C exists                        -> attach the alert to C; record "alert added" in C's history;
                                       recompute C's aggregates; raise C's priority if warranted (9.4)
else if a recently closed case exists  -> apply the re-open policy (7.4)
else                                -> create a new case for the party, attach the alert
```

| ID | Req | Level |
|---|---|---|
| CMR-CASE-01 | At most **one open case per party** at any time. Enforce it with a unique constraint (e.g. unique on party where status is open), not only in code, so two parallel ingest workers cannot create duplicates | MUST |
| CMR-CASE-02 | Alerts for the same party from different accounts, products, rules and business dates all attach to the party's open case | MUST |
| CMR-CASE-03 | Creating the case and attaching the first alert is **one transaction**; the alert must never exist unattached | MUST |
| CMR-CASE-04 | Each case keeps the list of its alerts with their original payload; the alert-to-case link is permanent and audited | MUST |
| CMR-CASE-05 | Case history records every system action (case created from alert X, alert Y added, reason) with timestamp and actor `SYSTEM` | MUST |
| CMR-CASE-06 | When a new alert is added to a case under investigation, notify the assigned analyst, mark the case "new activity" and show what changed | SHOULD |
| CMR-CASE-07 | **Transaction de-duplication.** Alerts may share transactions. Case-level totals, transaction counts and timelines MUST count each transaction **once**, keyed by `(transaction_id, posting_date)`. Never sum `evidence.total` across alerts | MUST |
| CMR-CASE-08 | A case must show, per alert, the rule, account, product, business date and evidence, and a combined, de-duplicated transaction list across all alerts, sortable by time, with transactions marked by the alerts that contain them | MUST |
| CMR-CASE-09 | Case-level summary: party, accounts involved, products, rules fired, alert count, date range of activity, de-duplicated totals in and out | MUST |
| CMR-CASE-10 | Do not merge cases of **different** parties automatically. Linking related parties (shared address, same beneficial owner, funds flowing between them) is an analyst action (a "linked case/party" reference), because the engine does not provide joint-owner or relationship data | MUST |

### 7.4 Re-open policy for recently closed cases

When an alert arrives for a party whose last case was closed recently, there are product choices. **Compliance to decide; configure, do not hard-code.**

| Last case outcome | Suggested default |
|---|---|
| Closed, no SAR (false positive) | If closed within N days (suggest 30), re-open the case and attach; otherwise new case linked to the previous one |
| Closed with SAR filed | **Always a new case linked to the SAR case**, flagged "subject of prior SAR". New alerts may be continuing activity (section 11) |
| Closed, SAR decision pending (case in SAR approval) | Attach to that case; the new alert must be considered before filing |

CMR-CASE-11 (MUST): the policy above is configurable (N days and per-outcome behavior), and every automatic decision is recorded in the case history.

### 7.5 Worked example: three alerts, one party, one case

File `samples/party-with-three-alerts.json` holds three real engine alerts for customer **CUS-5900 (Daniel Okafor)**:

| alert_id | Rule | Account | Evidence | Transactions |
|---|---|---|---|---|
| 20 | `LARGE_CASH_DAILY` | DEP-200001 (deposit) | total $11,000 | TX900003, TX900004 |
| 24 | `STRUCTURING_CASH_DEPOSITS` | DEP-200001 (deposit) | total $29,500, 4 txns | TX900001, TX900002, TX900003, TX900004 |
| 16 | `CARD_CASH_ADVANCE_VELOCITY` | CRD-200002 (card) | total $2,000, 4 advances | TX900011 to TX900014 |

Expected behavior: **one case** for CUS-5900. The naive sum of evidence totals is $42,500, which is **wrong**: TX900003 and TX900004 ($11,000) appear in both deposit alerts. The correct de-duplicated cash deposits are $29,500 (four transactions), and the card cash advances add $2,000, giving **$31,500 across 8 distinct transactions**. The case summary shows 3 alerts, 2 accounts, 2 products, 3 rules. This example is an acceptance test (section 17, AT-05).

### 7.6 Concurrency and ordering

- Process one delivery sequentially by `alert_id`, or ensure the unique open-case constraint (CMR-CASE-01) makes parallel workers safe: on a unique violation, re-read and attach.
- Deliveries may arrive out of date order (e.g. a revision of an older date). Do not assume `alert_id` order equals business-date order.

## 8. Alert handling inside a case

Each alert in a case carries its own status so that the analyst can decide per alert and the case still tells a story.

| Alert status | Meaning |
|---|---|
| `NEW` | Ingested, not yet reviewed |
| `IN_REVIEW` | Analyst working on it |
| `FALSE_POSITIVE` | Reviewed, explained as legitimate (reason code and comment mandatory) |
| `TRUE_POSITIVE` | Reviewed, suspicious activity confirmed (feeds SAR decision) |
| `WITHDRAWN_BY_ENGINE` | Engine announced `WITHDRAWN` (section 11.1); never auto-closed |

| ID | Req | Level |
|---|---|---|
| CMR-ALT-01 | A case cannot be closed while any alert is `NEW` or `IN_REVIEW` | MUST |
| CMR-ALT-02 | Disposing of an alert requires a reason code from a managed list and a free-text rationale | MUST |
| CMR-ALT-03 | Every disposition is recorded with user, time and the evidence the user viewed (audit) | MUST |
| CMR-ALT-04 | Dispositions per alert are stored in a form that supports the feedback report to the engine side (section 16) | MUST |

## 9. Case lifecycle, roles and prioritisation

### 9.1 States

```
 OPEN ──> IN_INVESTIGATION ──> PENDING_DECISION ──┬──> CLOSED_NO_SAR
   ^            │  ^                              │
   │            v  │                              └──> SAR_IN_PREPARATION ──> SAR_IN_REVIEW ──> SAR_APPROVED ──> SAR_FILED ──> CLOSED_SAR_FILED
   └──────── (re-open per 7.4)                                                      └── returned for changes ──┘
```

| State | Entry condition | Exit |
|---|---|---|
| `OPEN` | Created from an alert, unassigned | Assigned to an analyst |
| `IN_INVESTIGATION` | Analyst working (information requests, enrichment) | Analyst proposes a decision |
| `PENDING_DECISION` | All alerts disposed; analyst proposes "no SAR" or "SAR" | Reviewer approves or returns |
| `CLOSED_NO_SAR` | Reviewer approves no-SAR with documented rationale | Terminal (re-open per 7.4) |
| `SAR_IN_PREPARATION` | Decision is SAR | SAR draft complete |
| `SAR_IN_REVIEW` | Draft submitted for QA/approval | Approved, or returned for changes |
| `SAR_APPROVED`, `SAR_FILED` | Approved; filed and acknowledged | `CLOSED_SAR_FILED` |

### 9.2 Roles and segregation of duties

| Role | Can do |
|---|---|
| Analyst (L1) | Work cases, dispose of alerts, propose decisions, draft SAR |
| Senior analyst / reviewer (L2) | Approve no-SAR or SAR decisions, QA the narrative, return for changes |
| BSA Officer (or delegate) | Final SAR approval and filing authority |
| Auditor / examiner | Read-only everything, including history |
| Rule-tuning analyst | Read aggregated feedback only |

| ID | Req | Level |
|---|---|---|
| CMR-LIFE-01 | **Four-eyes:** the person who proposes a decision cannot approve it. Enforced by the system | MUST |
| CMR-LIFE-02 | Every state transition is recorded (who, when, from, to, reason) and cannot be edited | MUST |
| CMR-LIFE-03 | A `CLOSED_NO_SAR` decision requires a documented rationale, kept for the retention period | MUST |
| CMR-LIFE-04 | Case access is need-to-know; restricted cases (e.g. employee subjects) supported | SHOULD |
| CMR-LIFE-05 | Any change of assignee is recorded, with SLA clocks continuing | SHOULD |

### 9.3 Investigation support

CMR-INV-01 (SHOULD): the analyst can, from the case, view the party's profile and account details pulled from bank systems (section 12.2), add notes and attachments, request information from branches/relationship managers, and link related parties or cases. CMR-INV-02 (MUST): all notes and attachments are audited and retained with the case.

### 9.4 Prioritisation (the engine gives no severity)

The engine deliberately has no per-rule severity. Case management owns priority. Suggested starting inputs, configurable and tunable by Compliance:

- Number of distinct alerts and distinct rules in the case (corroboration across rules and products is a strong signal);
- De-duplicated total value;
- Presence of specific rules chosen by Compliance as high-risk (e.g. structuring);
- Age of the oldest unreviewed alert, and regulatory clocks (section 11);
- Whether the party is the subject of a prior SAR.

CMR-PRI-01 (MUST): priority is computed by the case management product, explainable (show the factors), and never written back as an engine "severity".

---

# PART C: SAR FILING

> **Compliance owns interpretation.** This part summarises what engineers need to design the workflow. Rules below reflect common US bank SAR practice (31 CFR 1020.320 and FinCEN SAR guidance) and must be confirmed by the bank's BSA Officer before build. Items marked **(confirm)** are especially likely to need local policy decisions.

## 10. When a SAR is required (decision support, not automation)

A true-positive decision is a **human** decision. The system supports it; it does not make it.

| Trigger (summary) | Threshold |
|---|---|
| Insider abuse involving any amount | Any amount |
| Violations aggregating $5,000 or more where a suspect can be identified | $5,000 |
| Violations aggregating $25,000 or more regardless of suspect | $25,000 |
| Transactions aggregating $5,000 or more involving potential money laundering or Bank Secrecy Act violations | $5,000 |

| ID | Req | Level |
|---|---|---|
| CMR-SAR-01 | The decision screen records: decision (SAR / no SAR), the trigger relied on, aggregate suspicious amount (from the de-duplicated transaction set, editable by the analyst with a reason), and rationale | MUST |
| CMR-SAR-02 | The system must **never auto-file** and never auto-decide from rule hits or amounts. Thresholds may be shown as guidance only | MUST |
| CMR-SAR-03 | A no-SAR decision records the rationale (e.g. explained by customer business, documented source of funds) | MUST |

## 11. SAR timelines and continuing activity (confirm)

| Clock | Rule of thumb | System behavior |
|---|---|---|
| Initial filing | Within **30 calendar days** of initial detection of facts that may constitute a basis for filing; up to **60** days if no suspect is identified (while one is sought) | Start the clock at the **date of initial detection** (set by compliance, default the earliest alert's business date in the case, editable with a reason); show countdown; warn at configurable thresholds (e.g. 10 and 5 days); escalate on breach |
| Continuing activity | Review at least every **90 days** after the previous SAR; file within **30 days** after the review (about 120 days from the previous filing) | After `SAR_FILED`, start a continuing-activity timer; when it fires, create a review task and, if new alerts arrive, a new linked case (7.4) |

| ID | Req | Level |
|---|---|---|
| CMR-SAR-04 | Deadline clocks are calculated by the system, visible on the case and on a dashboard, and every override is audited | MUST |
| CMR-SAR-05 | Breach of a deadline raises an alert to supervisors and is reportable | MUST |
| CMR-SAR-06 | A new alert for a party with a SAR filed in the last 120 days is routed per 7.4 and flagged "possible continuing activity" | SHOULD |

### 11.1 Withdrawn alerts (consumer policy)

`WITHDRAWN` means a re-run on corrected data would **not** raise that alert again. Do not delete the alert or silently close its case. Mark it `WITHDRAWN_BY_ENGINE`, notify the case owner, and require the analyst to confirm that the case still stands without it. If a SAR was already filed relying on withdrawn data, route to the BSA Officer. **(Compliance to confirm this policy.)**

## 12. SAR content: what comes from where

### 12.1 SAR structure (FinCEN SAR, electronic filing)

| SAR part | Content |
|---|---|
| Subject information | Name, TIN, DOB, address, ID, occupation, accounts involved, relationship to bank |
| Suspicious activity information | Date range, total amount, activity category(ies) and type(s), instrument types, cumulative amount |
| Financial institution information | Bank identity, branch(es) where activity occurred |
| Filer contact | Contact office and person |
| **Narrative** | Who, what, when, where, why and how; analyst-written, factual, complete |

### 12.2 Field sourcing

| SAR data | Source |
|---|---|
| Subject name, customer id, type, state, country | Alert `customer.*` (snapshot) **verified against** the party master |
| TIN, DOB, address, ID documents, occupation, phone | **Bank KYC/customer system** (not in the alert) |
| Joint owners and other involved parties | **Bank account system / analyst** (not in the alert) |
| Account numbers and product | Alert `account.id`, `product_type`; details from the bank's account system |
| Activity date range, aggregate amount, instrument types | **Derived** from the de-duplicated transaction set (CMR-CASE-07), editable by the analyst with a reason |
| Activity type / category | Analyst chooses; the rule gives a **starting suggestion** (12.3) |
| Narrative | Analyst; system may pre-fill a factual skeleton from the data (12.4) |
| Filing institution and contact | Configuration |

### 12.3 Starting suggestions by rule (analyst always decides)

| Rule | Typical starting category family |
|---|---|
| `LARGE_CASH_DAILY`, `STRUCTURING_CASH_DEPOSITS` | Structuring (and, with context, money laundering) |
| `RAPID_MOVEMENT_OF_FUNDS`, `DORMANT_REACTIVATION`, `NEW_COUNTERPARTY_COUNTRY` | Money laundering / suspicious use of accounts |
| `DEBIT_SPIKE_VS_BASELINE` | Unusual activity; fraud or money laundering depending on investigation |
| `CARD_CASH_ADVANCE_VELOCITY`, `CREDIT_BALANCE_REFUND` | Credit card related activity; potential fraud or bust-out schemes |
| `LOAN_EARLY_PAYOFF` | Loan-related; source of repayment funds |

The mapping to the current FinCEN category/type code list is a configuration table owned by Compliance, so that code list updates need no release.

### 12.4 Narrative guidance (system supports, analyst writes)

CMR-SAR-07 (SHOULD): offer a **narrative template** with sections (summary of suspicion; subject and relationships; accounts; description of activity with dates and amounts from the de-duplicated set; why it is suspicious; investigative steps and findings; action taken or recommended, including continuing review). Pre-filled facts are marked "system-generated, verify". The analyst owns the narrative.

## 13. SAR workflow, filing and records

| ID | Req | Level |
|---|---|---|
| CMR-SAR-08 | SAR draft is completed in the system with validation of required fields before submission for review | MUST |
| CMR-SAR-09 | QA review and BSA Officer approval before filing; four-eyes (CMR-LIFE-01); a returned draft keeps comments | MUST |
| CMR-SAR-10 | Filing is performed through the FinCEN BSA E-Filing channel. Whether this system generates the filing file and the filing is manual or integrated is an open decision (section 14). Record the **filing date, BSA ID/tracking number and acknowledgement** against the case | MUST |
| CMR-SAR-11 | **Confidentiality / no tipping off:** the existence of a SAR, and any information that would reveal it, must not be disclosed to the subject or to anyone not entitled. SAR flags and content are restricted to authorised roles, hidden from general case lists and from customer-facing systems; exports are logged | MUST |
| CMR-SAR-12 | Retain the SAR and **supporting documentation for five years** from the filing date (confirm), including no-SAR rationale records according to bank policy. Retention is configurable; deletion before expiry is prevented | MUST |
| CMR-SAR-13 | Supporting documentation (including raw alert payloads, evidence and analyst notes) must be reproducible on request from regulators | MUST |

---

# PART D: OTHER REQUIREMENTS

## 14. Open decisions

| # | Decision | Owner | Why it matters |
|---|---|---|---|
| D1 | Can case management hold a PostgreSQL connection for `LISTEN`? If not, polling interval | Case mgmt | Notification is the fast path; polling the safety net |
| D2 | Who is alerted when a business day has no delivery, and after what time | Operations / both | No "ready by" time is agreed; an escalation is needed anyway |
| D3 | Who performs production remediation of rejected alerts | Operations | Rejections are audited and escalate to CRITICAL after 24 hours |
| D4 | `WITHDRAWN` handling policy (11.1) | Compliance | Affects open and filed cases |
| D5 | Re-open policy N days and per-outcome behavior (7.4) | Compliance | Duplicate cases vs. missed continuity |
| D6 | Alert retention period in the engine vs. case management | Compliance / Legal | Case management must hold its own raw copy (CMR-ING-04) regardless |
| D7 | SAR filing: manual BSA E-Filing or file-generation integration | Compliance / Case mgmt | Scope of Part C |
| D8 | Whether to link joint owners at ingest | Case mgmt | Engine addresses primary customer only |
| D9 | Production load: up to 5,000 alerts per day and volumes around 10 million transactions per day were planned; not measured in production-scale | Both | Capacity of case creation and UI |

## 15. Non-functional requirements

| ID | Requirement |
|---|---|
| CMR-NFR-01 | Ingest a delivery of 5,000 alerts (about 1 to 5 MB per 1,000 alerts) in minutes, with paging of 500 to 1,000 per read, and without holding the whole delivery in memory |
| CMR-NFR-02 | Ingestion is restartable at any point with no duplicate alerts or cases |
| CMR-NFR-03 | Case creation per party must remain correct under parallel workers (CMR-CASE-01) |
| CMR-NFR-04 | Use a dedicated read-only database role for the interface; the engine side provides `case_mgmt_grants.sql` granting `SELECT` on views and `EXECUTE` on the two functions, nothing else |
| CMR-NFR-05 | Personal data in alerts (names) is protected at rest and in transit; access is logged |
| CMR-NFR-06 | Time stamps in alerts have no time zone; treat as the bank's local time and store as received |
| CMR-NFR-07 | Do not depend on engine tables other than the interface objects in section 3 |

## 16. Feedback to the engine side (tuning)

Rules are only as good as their outcomes. CMR-FB-01 (SHOULD): provide, at least monthly and on request, a report by `rule.code` and `rule.version`: alerts received, false positives, true positives, SARs filed, average time to disposition, and top false-positive reason codes. Aggregated figures only; no customer data. This lets Compliance tune thresholds (parameters are configuration, not code).

## 17. Acceptance tests

Use the sample files in `samples/` and the JSON schema as fixtures.

| ID | Scenario | Pass criteria |
|---|---|---|
| AT-01 | Ingest `delivery 1` (11 alerts) twice | 11 alerts stored, no duplicates; confirmation count 11 and checksum equal to the delivery checksum |
| AT-02 | Each `samples/alert-*.json` | Validates against `alert-payload.schema.json`; parsed fields present; unknown evidence key does not break ingest |
| AT-03 | An alert with `customer.name = null` | Ingested; party key still `customer.id` |
| AT-04 | Alert for a `customer.id` unknown to the party master | Handled per policy (create party stub or `reject_alerts` with reason) and audited; the delivery is not blocked |
| AT-05 | Party example (7.5): alerts 20, 24 then 16 | One case; 3 alerts; 2 accounts; de-duplicated transactions 8; cash deposit total $29,500 (not the $42,500 naive sum) |
| AT-06 | Same alert delivered twice, and two workers handling alerts of one party at once | One case, no duplicate alert records |
| AT-07 | Revision 2 of a business date arrives after revision 1 was confirmed | Alerts attach to the existing open cases; revision 2 confirmed separately |
| AT-08 | `WITHDRAWN` event for an alert in an open case | Alert marked withdrawn, case owner notified; case not closed automatically |
| AT-09 | Case closed no-SAR, then a new alert for the same party 10 days later | Behaves per the configured re-open policy; recorded in history |
| AT-10 | Analyst proposes SAR and attempts to approve their own decision | Blocked (four-eyes) |
| AT-11 | Close attempt with an alert still `NEW` | Blocked |
| AT-12 | SAR clock | Countdown starts at the earliest detection date; breach escalates |
| AT-13 | Case with SAR filed 20 days ago receives a new alert | New linked case flagged "prior SAR / possible continuing activity" |
| AT-14 | Reject path | `reject_alerts` with reason; appears in `v_alert_rejections`; delivery not confirmed until resolved |

## 18. Assumptions to confirm

1. A party is the primary customer on the alert. Joint owners are handled by analysts, not by ingestion (D8).
2. `customer.id` in alerts matches the case management party master identifier.
3. A delivery may be read many times until confirmed; once confirmed it disappears from `v_alert_export`, so keep your own copy.
4. Part C regulatory content (thresholds, timelines, retention, categories) is a summary and subject to Compliance confirmation.

---

## Appendix A: Sample files

| File | Content |
|---|---|
| `samples/alert-<rule>.json` (9) | One real payload for each rule (the nine in section 5) |
| `samples/party-with-three-alerts.json` | Three alerts for one party (section 7.5) |
| `samples/deliveries.json` | Two deliveries of one business date: revision 1 (11 alerts) and revision 2 (3 alerts), with rule counts and checksums |

Names, ids and amounts are fictional.

## Appendix B: Enumerations

| Field | Values |
|---|---|
| `account.product_type` | `CARD`, `LOAN`, `DEPOSIT` |
| `transactions[].direction` | `CREDIT`, `DEBIT` |
| `transactions[].txn_type` | `CASH_DEPOSIT, CASH_WITHDRAWAL, CARD_CASH_ADVANCE, CHECK_DEPOSIT, ACH_CREDIT, ACH_DEBIT, WIRE_IN, WIRE_OUT, INTERNAL_TRANSFER, POS_PURCHASE, ECOM_PURCHASE, CARD_PAYMENT, LOAN_DISBURSEMENT, LOAN_PAYMENT, LOAN_PAYOFF, FEE, INTEREST, REFUND, REVERSAL, OTHER` |
| `customer.type` | `INDIVIDUAL`, `ORGANIZATION` (others possible; treat as opaque) |
| Delivery status | `OPEN` (never visible), `READY`, `CONFIRMED`, `MISMATCH` |
| Event type | `WITHDRAWN` (more may be added; ignore unknown types after logging) |
| Confirmation channel | `DB`, `OPERATOR`, `FILE` |

## Appendix C: Glossary

| Term | Meaning |
|---|---|
| Alert | Engine statement that one rule found activity on one account on one business date |
| Delivery | All alerts of one business date (and revision), published together |
| Revision | A later delivery for the same business date carrying only new alerts |
| Party | The customer an alert is addressed to (primary customer of the account) |
| Case | The investigation unit in case management: one open case per party |
| SAR | Suspicious Activity Report filed with FinCEN |
| CTR | Currency Transaction Report (cash over $10,000); not generated by this system |
| Structuring | Breaking up cash transactions to avoid reporting thresholds |
| Tipping off | Disclosing to the subject that a SAR exists or is being considered |

## Appendix D: Change log

| Version | Date | Change |
|---|---|---|
| 1.0 draft | 2026-10-03 | First issue for case management review |
