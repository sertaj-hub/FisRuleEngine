# Machine-learning anomaly detection

Python module `fisre-ml/` plus the `ML_SCORE` rule template. Design: ADR-0009. Data contract: `specs/data-contract/ml-scores.md`.

| ID | Requirement | Status |
|---|---|---|
| REQ-ML-001 | Features for one posting day are computed with set-based SQL from `mst` for accounts that had activity on that day, one row per account. | Implemented |
| REQ-ML-002 | Features are point in time: transactions posted after the as-of day never change an account's features. | Implemented |
| REQ-ML-003 | Training builds one model per product type from sampled historical days and registers it in `aml.ml_model` (version, training window, row counts, parameters, artifact SHA-256). A new model becomes ACTIVE and retires the previous ACTIVE model of the same name. | Implemented |
| REQ-ML-004 | Scoring refuses a model artifact whose SHA-256 differs from the registry. | Implemented |
| REQ-ML-005 | The score is an anomaly percentile in [0,1] relative to the training population of the same product type; a more unusual account-day never scores lower than a more ordinary one. | Implemented |
| REQ-ML-006 | Each persisted score carries the top 3 features that make it unusual, with value, peer median and robust z-score. | Implemented |
| REQ-ML-007 | Scoring persists only scores at or above `store_min_score` and is idempotent: re-scoring a day with the same model replaces its rows. | Implemented |
| REQ-ML-008 | The `ML_SCORE` template raises a hit for each account whose score from the ACTIVE model on the as-of day is at or above `min_score`; evidence carries model, model_version, score, rank and top features; the account's transactions of that day are the alert transactions. | Implemented |
| REQ-ML-009 | Scores of a retired model version never produce hits. | Implemented |
| REQ-ML-010 | If an ACTIVE `ML_SCORE` rule finds no scores for the as-of day, the rule fails (recorded in `aml.rule_run`); it does not silently produce zero alerts. | Implemented |
| REQ-ML-011 | The ML rule ships in shadow mode (`DRAFT`): scores are recorded and visible in `aml.v_ml_scores`, and no alert is created until the rule is made ACTIVE. | Implemented |
| REQ-ML-012 | An evaluation command injects known suspicious patterns into synthetic data and reports recall and precision of the top-ranked accounts. Its result proves the pipeline works; it is not evidence of accuracy on real data. | Implemented |
| REQ-ML-013 | Supervised alert prioritisation from case dispositions. | Planned |
