# ADR-0009: Machine-learning anomaly detection, shadow mode first

Status: accepted

## Context
Rules find patterns we can describe. Money laundering also looks like "this account behaves unlike its peers and unlike itself". We have **no labelled history** (no confirmed suspicious activity, no SAR outcomes), so a supervised model cannot be trained honestly yet. Regulators expect models to be explainable, versioned and governed (model risk management, SR 11-7).

## Decision
1. **Unsupervised first.** One Isolation Forest per product type (CARD, LOAN, DEPOSIT) trained on historical account-days. Supervised alert prioritisation is designed (see below) but not built until case management produces dispositions.
2. **Python module `fisre-ml/`** (scikit-learn). It reads `mst`, writes only to `aml.ml_model` and `aml.ml_score`. It never writes alerts.
3. **Features are computed in SQL, set-based**, per account for one posting day, for accounts that had activity that day (same pruning as the rules). Point in time: nothing after the as-of day is read.
4. **Score = anomaly percentile** in [0,1]: the share of the training population (same product) that looks more normal than this account-day. 0.995 means "more unusual than 99.5% of training". It is stable across days and can be thresholded in configuration.
5. **Explainable:** every persisted score carries its top 3 features (value, peer median, robust z-score). This is a heuristic explanation, not an exact attribution, and is documented as such.
6. **Integration through the existing rule mechanism.** A new template `ML_SCORE` turns scores of the ACTIVE model at or above `min_score` into ordinary hits, so ML alerts keep the same grain, evidence, delivery, withdrawal and reconciliation behaviour. No consumer contract change; the evidence has new keys.
7. **Shadow mode is the default.** `specs/rules/ML_ANOMALY.yml` ships with `status: DRAFT`. Scores are recorded and reviewable (`aml.v_ml_scores`), no alert reaches case management. Going live = change the file to `ACTIVE` and `load-rules` (a new rule version, audited).
8. **Fail safe.** If an ACTIVE ML rule finds no scores for the as-of day, the rule fails (delivery stays OPEN, health reports it) rather than silently raising nothing.
9. **Integrity.** The model artifact's SHA-256 is stored in the registry and verified before scoring.

## Not decided here / later
- **Supervised prioritisation:** once case management feeds back TRUE/FALSE_POSITIVE per alert (case-management spec section 16), train a gradient-boosted ranker on alert features plus the anomaly score and use it to order the analyst queue. It must not suppress alerts without Compliance approval.
- Autoencoders and graph features (shared counterparties across accounts) are possible later experiments.

## Consequences
- Accuracy on real data is **unknown** until shadow mode has run and analysts have reviewed samples. Evaluation on synthetic data proves the pipeline, not real-world performance (REQ-ML-012).
- A model change is a governance event: new `model_version`, registry row, retained artifact.
- Python becomes a second runtime on the nightly path (train weekly or monthly, score nightly after promote).
