# Rule configuration UI and approvals

ADR-0010. API under `/api`, UI served at `/` by `FISRE_JOB=serve`.

| ID | Requirement | Status |
|---|---|---|
| REQ-RUI-001 | A rule version created through the API starts as `DRAFT` with source `UI`, numbered after the highest existing version, and is saved only if the template accepts its configuration. A new rule code needs a valid name and template; an existing code keeps its template. | Implemented |
| REQ-RUI-002 | Workflow: `DRAFT` to `PENDING_APPROVAL` (submit, by its author), then `ACTIVE` (approve) or `REJECTED` (reject, reason required); `ACTIVE` to `RETIRED` (retire, reason required). Any other transition is refused. | Implemented |
| REQ-RUI-003 | The approver must differ from the submitter, checked by the service and by a database constraint. | Implemented |
| REQ-RUI-004 | Approval activates the version and retires the previously active version of the same rule in one transaction, so there is never none or two. | Implemented |
| REQ-RUI-005 | Roles: `VIEWER`, `AUTHOR` and `APPROVER` may read; only `AUTHOR` may create, edit, submit and dry-run; only `APPROVER` may approve, reject and retire. No login gives 401, a missing role 403. | Implemented |
| REQ-RUI-006 | Every create, edit, submit, approve, reject, retire and dry-run is recorded in `aml.rule_audit` with actor, time and detail; the table cannot be updated or deleted. | Implemented |
| REQ-RUI-007 | A dry-run evaluates a proposed configuration over the last N posting days (1 to 31), reports per day the proposed alerts, the active version's alerts, accounts added and removed, and a sample of hits, and leaves no rows behind. | Implemented |
| REQ-RUI-008 | Approval requires a recorded dry-run of that version made after its last edit. | Implemented |
| REQ-RUI-009 | `GET /api/templates` describes each template's configuration fields (key, kind, required, help) so forms are generated; the fields of every template match the keys its validation accepts. | Implemented |
| REQ-RUI-010 | `load-rules` does not overwrite a rule whose latest version came from the UI; it logs a warning and skips it. | Implemented |
| REQ-RUI-011 | `export-rules` writes each rule's current version (active, else latest) to `<code>.yml`, keeping the `tests:` section of an existing file. | Implemented |
| REQ-RUI-012 | Passwords are stored as bcrypt hashes (`create-user` job, minimum 12 characters); the web server binds to localhost unless configured otherwise; the page and its scripts are served without login, the API never. | Implemented |
| REQ-RUI-013 | Corporate single sign-on, login lockout, asynchronous dry-runs. | Planned |
