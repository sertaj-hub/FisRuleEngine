import json
from datetime import timedelta

import numpy as np
import pytest
from psycopg import sql

from fisre_ml import features as F
from fisre_ml import synthetic
from fisre_ml.model import ModelIntegrityError, load_active, sha256_of, train
from fisre_ml.scoring import anomaly_percentile, score_day, score_frame
from tests.conftest import AS_OF

LIKE = synthetic.PREFIX + "%"


def _registry(conn, cfg):
    with conn.cursor() as cur:
        cur.execute(sql.SQL("SELECT model_version, status, artifact_sha256, train_rows, params FROM {a}.ml_model WHERE model_name = %s ORDER BY model_id")
                    .format(a=sql.Identifier(cfg.aml)), (cfg.model_name,))
        return cur.fetchall()


def test_training_registers_the_model_and_retires_the_previous_one(world):  # REQ-ML-003
    cfg, conn, _, artifact = world
    rows = _registry(conn, cfg)
    assert [r[1] for r in rows].count("ACTIVE") == 1
    version, status, sha, train_rows, params = rows[-1]
    assert version == artifact["version"] and status == "ACTIVE"
    assert sha == sha256_of(__import__("pathlib").Path(artifact["path"])) and train_rows > 1000
    assert set(params["products"]) == {"DEPOSIT", "CARD"} and params["n_trees"] == 200

    second = train(conn, cfg, AS_OF - timedelta(days=1), history_days=45, max_days=10, account_like=LIKE, seed=3)
    rows = _registry(conn, cfg)
    assert [r[1] for r in rows].count("ACTIVE") == 1
    assert rows[-1][0] == second["version"] and rows[-1][1] == "ACTIVE"
    assert rows[-2][1] == "RETIRED"


def test_scoring_refuses_a_tampered_artifact(world):  # REQ-ML-004
    cfg, conn, _, _ = world
    train(conn, cfg, AS_OF - timedelta(days=1), history_days=45, max_days=5, account_like=LIKE, seed=5)   # a fresh ACTIVE model
    _, version = load_active(conn, cfg)             # verifies, loads
    path = next(cfg.model_dir.glob(f"*{version}*.joblib"))
    original = path.read_bytes()
    try:
        path.write_bytes(original + b"tampered")
        with pytest.raises(ModelIntegrityError, match="does not match the registry"):
            load_active(conn, cfg)
        with pytest.raises(ModelIntegrityError):
            score_day(conn, cfg, AS_OF, account_like=LIKE)
    finally:
        path.write_bytes(original)
    load_active(conn, cfg)


def test_percentile_is_monotone_in_unusualness():  # REQ-ML-005
    training = np.sort(np.random.default_rng(1).normal(size=1000))        # higher = more normal
    probes = np.array([3.0, 0.0, -0.5, -3.0, -10.0])                      # decreasing normality
    p = anomaly_percentile(training, probes)
    assert np.all(np.diff(p) >= 0) and 0.0 <= p.min() and p.max() <= 1.0
    assert p[0] < 0.01 and p[-1] == 1.0


def test_known_suspicious_accounts_score_higher_than_ordinary_ones(world):  # REQ-ML-005
    cfg, conn, w, artifact = world
    df = F.extract(conn, cfg.mst, AS_OF, LIKE)
    scored = score_frame(df, artifact)
    inj = scored[scored["account_id"].isin(w.injected)]
    rest = scored[~scored["account_id"].isin(w.injected)]
    assert inj["score"].median() > rest["score"].quantile(0.95)
    assert (scored["score"].between(0, 1)).all()
    dep = scored[scored["product_type"] == "DEPOSIT"]
    assert list(dep["rank_in_product"]) == sorted(dep["rank_in_product"]) and dep["score"].is_monotonic_decreasing


def test_scores_explain_themselves(world):  # REQ-ML-006
    cfg, conn, w, artifact = world
    scored = score_frame(F.extract(conn, cfg.mst, AS_OF, LIKE), artifact)
    row = scored[scored["account_id"] == "EVAL-D0000"].iloc[0]             # structuring
    ex = row["explanation"]
    assert len(ex) == 3
    assert all({"feature", "value", "peer_median", "z"} <= set(e) for e in ex)
    assert any("cash" in e["feature"] or "near_threshold" in e["feature"] for e in ex)
    assert all(abs(e["z"]) <= 50 for e in ex)                              # bounded, even for features constant in training
    json.dumps(ex)


def test_only_high_scores_are_stored_and_rescoring_replaces(world):  # REQ-ML-007
    cfg, conn, w, _ = world
    train(conn, cfg, AS_OF - timedelta(days=1), history_days=45, max_days=30, account_like=LIKE, seed=7)
    first = score_day(conn, cfg, AS_OF, store_min_score=0.99, account_like=LIKE)
    second = score_day(conn, cfg, AS_OF, store_min_score=0.99, account_like=LIKE)
    with conn.cursor() as cur:
        cur.execute(sql.SQL("SELECT COUNT(*), MIN(score), MAX(rank_in_product) FROM {a}.ml_score WHERE model_version = %s AND as_of_date = %s")
                    .format(a=sql.Identifier(cfg.aml)), (second["model_version"], AS_OF))
        n, lowest, _ = cur.fetchone()
    assert first["stored"] == second["stored"] == n
    assert 0 < n < first["accounts_scored"] * 0.1
    assert float(lowest) >= 0.99
