# ADR-0006: Alert handoff through a database view and an acknowledge function

Status: accepted

Case management reads alerts from the database. There is no file drop and no queue.

- A view is the contract, so tables can change without breaking the consumer. Columns are stable and tested.
- Acknowledgement is an explicit function call by the consumer after it has ingested, not something the engine guesses. Until then an alert stays visible; after it, the alert is frozen.
- The payload is self-contained: rule, customer snapshot, evidence and linked transactions. The consumer never needs to read `mst`. Linked transactions carry their posting date, so the view reads them from a single partition by primary key.
- The customer snapshot is stored on the alert at creation, so later customer updates cannot change what was alerted.
- Consumer access is read-only on the view plus execute on the function.

Alternatives not chosen: file export (the consumer prefers the database), a queue (not in scope for now), the consumer updating `aml.alert` directly (couples it to our tables).
