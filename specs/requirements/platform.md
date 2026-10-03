# Platform requirements

Scope: US BSA/AML, batch detection for card, loan and deposit accounts. Out of scope: sanctions
and watchlist screening, KYC risk scoring, real-time detection.

| ID | Requirement | Status |
|---|---|---|
| REQ-CFG-001 | The database vendor (postgresql, mysql, oracle) and the physical names of the stg, mst and aml schemas are chosen by configuration only, with no code change. | Implemented |
| REQ-CFG-002 | An unsupported vendor value stops the application at startup with a clear message. | Implemented |
| REQ-DB-001 | Schema migrations run automatically at startup against the configured vendor and create all stg, mst and aml tables plus seed reference data. | Implemented |
| REQ-RUN-001 | Every batch run is recorded in `aml.batch_run` (status SUCCESS or FAILED) with promoted and rejected counts per entity. | Implemented |
| REQ-RUN-002 | The job to run is selected by `FISRE_JOB`; the process exits non-zero on failure. | Planned |
