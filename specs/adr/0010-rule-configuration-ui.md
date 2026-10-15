# ADR-0010: Rule configuration UI with approvals, audit and dry-run

Status: accepted

## Context
Rules are YAML templates (ADR-0004). Compliance staff must be able to change thresholds without editing files or releasing code. A threshold change on an AML rule is a regulated change: it needs a trail of who proposed it, who approved it, and what effect it would have had.

## Decision
1. **The database is the source of truth for rule versions** (`aml.rule`). Versions created in the UI carry `source = 'UI'`; versions loaded from `specs/rules/*.yml` carry `source = 'YAML'`. A version is immutable once submitted; a change is always a new version.
2. **Workflow:** `DRAFT` → `PENDING_APPROVAL` → `ACTIVE` or `REJECTED`; an `ACTIVE` version can be `RETIRED`. Approval retires the previously active version of the same rule in the same transaction.
3. **Four-eyes:** the approver must differ from the submitter. Enforced in the service and again by a database check constraint.
4. **Dry-run before approval:** a proposed configuration is run over the most recent posting days (default 7, maximum 31) and compared with the active version: alerts per day, accounts added and removed, a sample of hits. It runs in a transaction that is always rolled back and never writes alerts. Approval requires a recorded dry-run of the exact version after its last edit.
5. **Same validation as the engine:** the UI can only save what the template's `validate()` accepts; values stay bound parameters. The form is generated from template metadata (`Template.fields()`), so a new template needs no UI change.
6. **Audit:** every create, edit, submit, approve, reject, retire and dry-run is appended to `aml.rule_audit` (actor, time, detail). The table is append-only (trigger).
7. **YAML stays useful.** `load-rules` never overwrites a rule whose latest version came from the UI (it warns and skips). `export-rules` writes the database rules back to YAML, keeping the `tests:` scenarios of existing files, so the change can be committed to git. Test scenarios remain in git; the build still runs them.
8. **Access:** a REST API under `/api` and a static single-page UI served by the same process (`FISRE_JOB=serve`). Login is HTTP Basic against `aml.rule_user` (bcrypt) with roles `VIEWER`, `AUTHOR`, `APPROVER`. The server binds to `127.0.0.1` by default and must sit behind TLS (reverse proxy) when exposed. Batch jobs stay non-web.

## Not done here
* Corporate single sign-on (OIDC/SAML): none was available. The user store is replaceable behind Spring Security.
* Login lockout / rate limiting (planned with SSO).
* Asynchronous dry-runs. At 10M transactions a day a 31-day dry-run is heavy; it is synchronous with a 5-minute timeout for now.
* A React front end. The first slice is plain HTML/JavaScript with no build step, talking only to the REST API, so it can be replaced without backend change.
* Scenario editing in the UI.
