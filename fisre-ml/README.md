# fisre-ml: anomaly scoring for the rule engine

Unsupervised, explainable account-behaviour model (ADR-0009, `specs/requirements/ml.md`). It reads `mst`, writes `aml.ml_model` and `aml.ml_score`, and never writes alerts. The Java engine turns scores into alerts through the `ML_SCORE` rule template, **in shadow mode** until `specs/rules/ML_ANOMALY.yml` is changed from `DRAFT` to `ACTIVE`.

```
mst.txn ──[features: set-based SQL, one posting day]──► Isolation Forest per product ──► percentile score + top-3 features
                                                                                         │
                                          aml.ml_score ◄───────────────────────────────┘
                                                │ (ACTIVE model, score ≥ min_score)
                                  Java ML_SCORE template ──► ordinary alert (rule ML_ANOMALY) ──► delivery ──► case management
```

## Setup and use

```bash
cd fisre-ml && pip install -e '.[test]'
export FISRE_DB_URL=jdbc:postgresql://localhost:5432/fisre FISRE_DB_USER=fisre FISRE_DB_PASSWORD=... FISRE_ML_MODEL_DIR=/var/lib/fisre/models
fisre-ml train --to 2026-09-30            # train on up to 30 sampled posting days from the last 90; registers the model ACTIVE
fisre-ml score --as-of 2026-09-30         # posting day = business date minus posting offset; stores scores >= 0.95
fisre-ml evaluate                         # injects known patterns into a rolled-back synthetic world; prints recall
python -m pytest                          # needs the PostgreSQL with the engine schema (run the Java build or any job once to migrate)
```

Nightly order: `promote` → `fisre-ml score --as-of <posting day>` → `detect`. Train weekly or monthly. If the ML rule is ACTIVE and scoring did not run, the rule fails (delivery stays OPEN, health reports it) instead of silently raising nothing.

## How the score works

* **Features** (per account, one posting day, SQL): day counts and totals (credit, debit, cash, max), 7-day counts/totals, distinct foreign counterparty countries, foreign share, flow-through ratio, cash just under $10,000, round-amount share, and today's total against the account's own 30-day average. Amounts and counts are `log1p`-transformed.
* **Model:** one Isolation Forest per product type (CARD, LOAN, DEPOSIT), 200 trees. A product with fewer than 200 training rows is skipped and reported.
* **Score:** share of the training population (same product) that looks *more normal* than this account-day, 0 to 1. 0.995 = more unusual than 99.5% of training. The same threshold means the same thing every day.
* **Explanation:** the 3 features furthest from the peer median in robust z-score (bounded to ±50). A heuristic, not an exact attribution; present it as "why to look", not "why guilty".
* **Governance:** every model is a registry row (training window, rows, parameters, artifact SHA-256). The artifact is a pickle, so scoring loads it only after the hash matches the registry; keep the model directory writable only by the batch user.

## Honest limits

* No labels exist yet, so accuracy on real data is **unknown**. Shadow mode exists to measure it: review the top of `aml.v_ml_scores` with analysts before going live.
* Isolation Forests cannot split on a feature that is constant in the training data. A product whose training days contained no cash advances at all ranks a burst of them first within the product but may not reach a 0.99 score. The evaluation shows exactly this for the synthetic card bursts. Use per-product review and consider rank-based cutoffs when tuning `min_score`.
* Peers are "same product type". Customer-type, segment or branch peer groups are the obvious next improvement.
* The synthetic evaluation proves the pipeline end to end; it says nothing about real customers.
