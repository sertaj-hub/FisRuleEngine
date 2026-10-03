# ML scores (engine-internal tables, ADR-0009)

Not part of the case management contract. Case management only ever sees an ML alert as an ordinary alert whose `rule.code` is `ML_ANOMALY`.

**`aml.ml_model`**: `model_id`, `model_name`, `model_version` (unique), `status` (ACTIVE | RETIRED; one ACTIVE per name), `trained_ts`, `train_from`, `train_to`, `train_rows`, `params` (JSON: features, hyper-parameters, per product statistics), `artifact_path`, `artifact_sha256`, `created_by`.

**`aml.ml_score`**: `model_version`, `as_of_date` (posting day), `account_id`, `product_type`, `score` (0 to 1, anomaly percentile), `rank_in_product` (1 = most unusual), `explanation` (JSON array of top features), `scored_ts`. Primary key `(model_version, as_of_date, account_id)`. Only scores at or above `store_min_score` are kept.

**`aml.v_ml_scores`**: scores of the ACTIVE model with model name, for shadow-mode review.

**Evidence of an ML alert**: `model`, `model_version`, `score`, `rank`, `top_features` (array of `{feature, value, peer_median, z}`), `min_score`.

**Template `ML_SCORE` config**: `model` (default `account_anomaly`), `min_score` (0 to 1, required), optional `product_types`.
