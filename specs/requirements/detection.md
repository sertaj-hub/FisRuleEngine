# Detection run

`FISRE_JOB=detect FISRE_BUSINESS_DATE=YYYY-MM-DD` evaluates the active rules over `mst` with windows ending on that date.

| ID | Requirement | Status |
|---|---|---|
| REQ-DET-001 | For each active rule, each account that hits gets one alert for the business date, addressed to the account's primary customer, with the rule code and version, a JSON evidence document and the list of transactions behind it (each link carries the posting date so details can be read from one partition). | Implemented |
| REQ-DET-002 | Detection for a business date is refused unless that date has a live (promoted) batch. | Implemented |
| REQ-DET-003 | Re-running a business date replaces the rule's alerts of the delivery that is still `OPEN`; alerts of a published delivery are never changed (REQ-DLV-006). There are never two alerts for the same rule, account and date. | Implemented |
| REQ-DET-004 | An alert already handed off to case management is never deleted or duplicated by a re-run. | Implemented |
| REQ-DET-005 | With `suppress_days` = N, an account that hit the same rule within the previous N days gets no new alert. | Implemented |
| REQ-DET-006 | A rule that fails is recorded as FAILED in `aml.rule_run` and does not stop the other rules; the job exits non-zero at the end. | Implemented |
| REQ-DET-007 | `aml.rule_run` records, per rule and run, the business date, status, timings and alerts created. | Implemented |
| REQ-DET-008 | Detection handles 10M transactions per day within the agreed window (measured by the benchmark). | Planned |
| REQ-DET-009 | Active rules run concurrently, up to `detect-parallelism`; each rule is its own transaction, and results do not depend on the parallelism. | Implemented |
| REQ-DET-010 | A rule's queries read only the master partitions inside its window (partition pruning), and candidate accounts are found from the as-of day's partition only. | Implemented |
| REQ-DET-011 | Transactions linked to an alert are capped at `max-evidence-txns` (default 200) per alert; the evidence JSON still carries the true totals. | Implemented |
| REQ-DET-012 | A rule's windows end on the posting day, which is the business date minus `posting-offset-days`; alerts carry the business date. | Implemented |
