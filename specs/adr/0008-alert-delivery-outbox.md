# ADR-0008: Alert delivery as a reconciled outbox

Status: accepted (refines ADR-0006)

Volume is small (300 to 5,000 alerts a day) and all alerts of a date are created within minutes of each other in the nightly run, so the design optimises correctness and reconciliation, not throughput.

- **One delivery per business date, published when detection finishes.** Streaming each alert as it is created would expose partial days, and a re-run (which replaces unsent alerts) could remove something already pushed. A delivery is invisible while `OPEN`.
- **Control totals.** On publish the engine records the alert count, the count per rule and a checksum of the alert ids. The consumer recomputes them from what it stored and calls `confirm_delivery`; the engine compares. A zero-alert date is still published, so "no alerts" is distinguishable from "not run".
- **Frozen once published.** A re-run never changes a `READY` or later delivery. New hits form the next revision; a re-run that adds nothing creates none. Alerts that stop hitting are reported by `WITHDRAWN` events in an append-only event table, so the immutable alert row stays as it was handed over and the consumer decides what to do.
- **Failure is visible, not partial.** If a rule fails the delivery stays `OPEN`; the health check reports it and the next successful run publishes.
- **Rejections are explicit.** The consumer can say which alerts it could not ingest and why; they are recorded and surfaced, never dropped.
- **Transport-agnostic.** The delivery tables and views are an outbox. If case management later needs a file, an API or Kafka, a relay job reads the same outbox; detection does not change.

Not chosen: Kafka (no second consumer yet, extra infrastructure), engine-pushed REST (couples the nightly run to the consumer's availability, needs retry and idempotency machinery).

## Addendum: availability and rejections

- **No fixed ready time.** The consumer is notified (`aml_delivery_ready`) as soon as a delivery is published and starts reading and ingesting immediately. The notification is not durable, so a consumer also polls as a safety net. No consumer-side wait or schedule is needed.
- **Rejections are an audit event with a tracked remediation.** A rejection (reason mandatory) creates a durable notice per delivery, a notification (`aml_alert_rejected`) and a health finding (warning, critical after 24 h). The engine has no always-on process, so these three together are how "the rule engine is told". Operations remediate manually in production and record the outcome with a mandatory note: `FIXED_REREAD` (the consumer re-reads and confirms the full count) or `HANDLED_MANUALLY` (the alerts are excluded from the delivery's control totals so it can close, with the exclusion recorded). Notices are never deleted.
