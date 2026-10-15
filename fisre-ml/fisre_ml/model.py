"""Training and the model registry (REQ-ML-003, REQ-ML-004).

One Isolation Forest per product type. The artifact holds the forests plus the training score distribution (for the
percentile score) and robust per-feature statistics (for explanations). Its SHA-256 is stored in aml.ml_model and checked
before loading, because the artifact is a pickle and must never be loaded from a file the registry does not vouch for.
"""
from __future__ import annotations

import hashlib
import json
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import psycopg
from psycopg import sql
from sklearn.ensemble import IsolationForest

from . import features as F
from .config import Config

N_TREES = 200
MIN_ROWS_PER_PRODUCT = 200


class ModelIntegrityError(RuntimeError):
    pass


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _training_days(conn: psycopg.Connection, mst: str, to_date: date, history_days: int, max_days: int, like: str) -> list[date]:
    with conn.cursor() as cur:
        cur.execute(sql.SQL("SELECT DISTINCT posting_date FROM {m}.txn WHERE posting_date BETWEEN %s AND %s AND account_id LIKE %s ORDER BY 1")
                    .format(m=sql.Identifier(mst)), (to_date - timedelta(days=history_days - 1), to_date, like))
        days = [r[0] for r in cur.fetchall()]
    if len(days) > max_days:                       # even, deterministic sample across the window
        days = [days[i] for i in np.linspace(0, len(days) - 1, max_days).round().astype(int)]
    return days


def _fit_product(X: np.ndarray, seed: int) -> dict:
    forest = IsolationForest(n_estimators=N_TREES, random_state=seed, n_jobs=-1).fit(X)
    normality = np.sort(forest.score_samples(X))     # higher = more normal
    median = np.median(X, axis=0)
    mad = np.median(np.abs(X - median), axis=0)
    # floor: a feature that is constant in training (MAD and std 0) must not turn tiny changes into astronomic z-scores
    scale = np.maximum(np.maximum(1.4826 * mad, 0.25 * X.std(axis=0)), 0.25)
    return {"forest": forest, "train_normality": normality, "median": median, "scale": scale, "rows": int(len(X))}


def train(conn: psycopg.Connection, cfg: Config, to_date: date, history_days: int = 90, max_days: int = 30, seed: int = 7,
          account_like: str = "%", register: bool = True) -> dict:
    """Train on up to ``max_days`` posting days from the ``history_days`` ending at ``to_date``.

    Returns the artifact dict (with ``version`` and ``path`` when registered).
    """
    days = _training_days(conn, cfg.mst, to_date, history_days, max_days, account_like)
    if not days:
        raise ValueError(f"no transactions in the {history_days} days to {to_date}; nothing to train on")
    frames = [F.extract(conn, cfg.mst, d, account_like) for d in days]
    data = pd.concat([f for f in frames if len(f)], ignore_index=True)
    products: dict[str, dict] = {}
    skipped: dict[str, int] = {}
    for product, part in data.groupby("product_type"):
        if len(part) < MIN_ROWS_PER_PRODUCT:
            skipped[str(product)] = int(len(part))
            continue
        products[str(product)] = _fit_product(F.transform(part), seed)
    if not products:
        raise ValueError(f"no product has at least {MIN_ROWS_PER_PRODUCT} training rows (found {skipped})")

    version = f"v{to_date:%Y%m%d}-{datetime.now(timezone.utc):%H%M%S}"
    artifact = {"version": version, "features": F.FEATURES, "products": products}
    if not register:
        return artifact

    cfg.model_dir.mkdir(parents=True, exist_ok=True)
    path = (cfg.model_dir / f"{cfg.model_name}-{version}.joblib").resolve()
    joblib.dump(artifact, path)
    digest = sha256_of(path)
    params = {"features": F.FEATURES, "n_trees": N_TREES, "seed": seed, "training_days": len(days), "history_days": history_days,
              "products": {p: {"rows": v["rows"]} for p, v in products.items()}, "skipped_products": skipped}
    aml = sql.Identifier(cfg.aml)
    with conn.transaction():
        conn.execute(sql.SQL("UPDATE {a}.ml_model SET status = 'RETIRED' WHERE model_name = %s AND status = 'ACTIVE'").format(a=aml), (cfg.model_name,))
        conn.execute(sql.SQL("INSERT INTO {a}.ml_model (model_name, model_version, status, train_from, train_to, train_rows, params, artifact_path,"
                             " artifact_sha256) VALUES (%s, %s, 'ACTIVE', %s, %s, %s, %s::jsonb, %s, %s)").format(a=aml),
                     (cfg.model_name, version, days[0], days[-1], len(data), json.dumps(params), str(path), digest))
    artifact["path"] = str(path)
    return artifact


def load_active(conn: psycopg.Connection, cfg: Config) -> tuple[dict, str]:
    """The ACTIVE model's artifact, after verifying its SHA-256 against the registry (REQ-ML-004)."""
    with conn.cursor() as cur:
        cur.execute(sql.SQL("SELECT model_version, artifact_path, artifact_sha256 FROM {a}.ml_model WHERE model_name = %s AND status = 'ACTIVE'")
                    .format(a=sql.Identifier(cfg.aml)), (cfg.model_name,))
        row = cur.fetchone()
    if row is None:
        raise LookupError(f"no ACTIVE model named '{cfg.model_name}'; run: fisre-ml train")
    version, path, expected = row
    p = Path(path)
    if not p.is_file():
        raise ModelIntegrityError(f"model artifact {p} is missing")
    actual = sha256_of(p)
    if actual != expected.strip():
        raise ModelIntegrityError(f"model artifact {p} does not match the registry (SHA-256 {actual} != {expected.strip()}); refusing to load it")
    return joblib.load(p), version
