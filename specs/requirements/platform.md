# Platform requirements

Scope: US BSA/AML, batch detection for card, loan and deposit accounts. Out of scope: sanctions
and watchlist screening, KYC risk scoring, real-time detection.

| ID | Requirement | Status |
|---|---|---|
| REQ-CFG-001 | The physical names of the stg, mst and aml schemas are chosen by configuration only, with no code change. | Implemented |
| REQ-CFG-002 | An unsupported database vendor value stops the application at startup with a clear message (only postgresql is supported for now, ADR-0002). | Implemented |
| REQ-DB-001 | Schema migrations run automatically at startup and create all stg, mst and aml tables plus seed reference data. | Implemented |
| REQ-RUN-002 | The job to run is selected by `FISRE_JOB` (none, promote, clean, reopen) with `FISRE_BATCH_ID`; an unknown job or a missing batch id stops the run with an error and a non-zero exit. | Implemented |
