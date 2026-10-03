# History load, retention and benchmark

History starts at one month (about 30 daily batches) and grows to 13 months. Detection is not run for history (design choice, ADR-0005).

| ID | Requirement | Status |
|---|---|---|
| REQ-HIS-001 | `promote-loaded` promotes every `LOADED` batch in business-date order: the earliest alone first (it carries the customer and account snapshot), then the rest in parallel (`parallelism`). The customer and account upserts plus the partition swap are serialized by a lock; the heavy work (validation, partition build) runs in parallel. If the first batch fails the rest are not started; any failure exits non-zero. | Implemented |
| REQ-RET-001 | `retain` drops master transaction partitions older than `retention-months` (default 13) before the as-of date of the given business date, and no others. | Implemented |
| REQ-BEN-001 | `generate` creates a synthetic batch (customers, accounts, transactions across the three products) of a configured size directly in SQL, for benchmarks and local runs. | Implemented |
