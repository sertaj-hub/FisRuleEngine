# Benchmark results for ADR-0005 (measured, with limits)

Environment: one 4-core sandbox, 15 GB RAM, PostgreSQL 16 with **default settings** (128 MB shared buffers, 4 MB work_mem), engine and database on the same machine.
Data: synthetic (`generate` job), 5,000,000 transactions and 500,000 accounts (167k each of deposit, card and loan) on one posting day, 12 transaction types, 10 transactions per account on average.

| Step | Time | Notes |
|---|---|---|
| Generate batch (SQL only) | 11 s | 5M transactions, 333k customers, 500k accounts |
| Promote (validate, build partition, swap, drop staging) | 29 to 32 s | day 1 with customers and accounts; day 2 (transactions only) 29 s |
| Replace a day with a corrected batch (partition swap) | 32 s | old partition detached and dropped in the same transaction |
| Detect, 7 rules in parallel (4 workers) | 43 s | was 144 s before nested loops were disabled (one rule took over 110 s) |
| One day's partition on disk | 818 MB | 5M rows with primary key and account index |

Bugs the benchmark found and fixed: an OR of correlated EXISTS and a per-row MAX() subquery in validation and upsert (each planned as one sub-select per row, never finished at 500k rows); a nested-loop plan chosen from a 1-row estimate for a 160k-row candidate set; and a generator that put all rows on one account (caught because zero alerts were implausible).

What this does **not** show:
- 10M a day was not measured. Linear extrapolation of promote is about 60 s and detect about 90 s, but joins and sorts do not always scale linearly, and a production server will differ in both directions (more cores and memory, but real data skew and concurrent load).
- The alert counts (440k from 5M transactions) are an artifact of synthetic data: large random amounts, and no history, so every deposit account looks dormant. Real volumes will be far lower.
- No history was loaded: windows of 5 to 7 days read one partition here, not 5 to 7. Detection over a full 13 months has not been timed.
- Single machine, no concurrent ETL load or operational queries.

Next measurement to take, on target-like hardware: 10M transactions a day with 30 daily partitions loaded (the planned history start), then timing promote, detect and a `retain` run.
