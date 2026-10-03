# Rule framework

A rule is a configured instance of a generic **template** (ADR-0004): template code, scope filters and parameters (JSON), a name and a status.
The source of truth is one YAML file per rule in `specs/rules/`, loaded into `aml.rule` by `FISRE_JOB=load-rules`.
There is no per-rule severity. Config reference: `data-contract/rule-config.md`.

| ID | Requirement | Status |
|---|---|---|
| REQ-RULE-001 | `load-rules` reads every rule spec in the rules directory and stores it in `aml.rule` as a versioned row (template, status, name, description, `suppress_days`, JSON config). | Implemented |
| REQ-RULE-002 | Loading is idempotent: an unchanged spec creates no new version; a changed spec creates the next version and retires the previously active one. | Implemented |
| REQ-RULE-003 | A spec with an unknown template, missing or invalid parameter, unknown config key or bad filter is rejected at load time with a message naming the rule, and nothing from that load is stored. | Implemented |
| REQ-RULE-004 | Only rules with status `ACTIVE` run during detection. | Implemented |
| REQ-RULE-005 | Template `AGGREGATE`: accounts whose matching transactions in a window reach a minimum count and a minimum total. | Implemented |
| REQ-RULE-006 | Template `FLOW_THROUGH`: accounts where matching credits and then matching debits in a window reach a minimum inflow and an outflow percentage of it. | Implemented |
| REQ-RULE-007 | Template `SEQUENCE`: accounts with matching "then" events on the business date preceded, within N days, by matching "first" events, with amount conditions. | Implemented |
| REQ-RULE-008 | Template `DORMANT_REACTIVATION`: accounts open longer than N days, with no transactions in the previous N days, that transact on the business date above a minimum. | Implemented |
| REQ-RULE-009 | Every rule spec carries test scenarios (at least one expecting an alert and one expecting none) that the build runs against the database, and every template is used by at least one spec. | Implemented |
| REQ-RULE-010 | Rules are created and edited from a UI. | Planned |
