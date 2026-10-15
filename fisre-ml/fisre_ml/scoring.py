"""Scoring one posting day (REQ-ML-005, 006, 007)."""
from __future__ import annotations

import json
from datetime import date

import numpy as np
import pandas as pd
import psycopg
from psycopg import sql

from . import features as F
from .config import Config
from .model import load_active

TOP_FEATURES = 3


def anomaly_percentile(train_normality_sorted: np.ndarray, normality: np.ndarray) -> np.ndarray:
    """Share of the training population that looks MORE normal than each sample: 0 = most ordinary, 1 = most unusual."""
    n = len(train_normality_sorted)
    more_normal = n - np.searchsorted(train_normality_sorted, normality, side="right")
    return more_normal / n


def score_frame(df: pd.DataFrame, artifact: dict, explain_min: float = 0.0) -> pd.DataFrame:
    """Adds ``score``, ``rank_in_product`` and ``explanation`` (a list of dicts) to the account rows of one day.

    Explanations are built only for scores at or above ``explain_min`` (the rest get an empty list), which keeps scoring
    of millions of ordinary accounts cheap. Products the model was not trained on are not scored (they are absent).
    """
    out = []
    for product, part in df.groupby("product_type"):
        m = artifact["products"].get(str(product))
        if m is None:
            continue
        X = F.transform(part)
        norm = m["forest"].score_samples(X)
        part = part.copy()
        part["score"] = anomaly_percentile(m["train_normality"], norm)
        # ties are broken by raw normality so that the more unusual account always ranks first
        part["_norm"] = norm
        part = part.sort_values(["score", "_norm"], ascending=[False, True]).reset_index(drop=True)
        part["rank_in_product"] = np.arange(1, len(part) + 1)
        z = (F.transform(part) - m["median"]) / m["scale"]
        part["explanation"] = [_explain(part.iloc[i], z[i], m) if part.at[i, "score"] >= explain_min else [] for i in range(len(part))]
        out.append(part.drop(columns="_norm"))
    return pd.concat(out, ignore_index=True) if out else df.iloc[0:0].assign(score=[], rank_in_product=[], explanation=[])


def _explain(row: pd.Series, z_row: np.ndarray, m: dict) -> list[dict]:
    """The features furthest from the peer median in robust z-score. A heuristic explanation, not an exact attribution."""
    order = np.argsort(-np.abs(z_row))[:TOP_FEATURES]
    result = []
    for i in order:
        f = F.FEATURES[i]
        result.append({"feature": f, "value": round(float(row[f]), 4),
                       "peer_median": round(F.untransform_value(f, float(m["median"][i])), 4), "z": round(float(np.clip(z_row[i], -50, 50)), 2)})
    return result


def score_day(conn: psycopg.Connection, cfg: Config, as_of: date, store_min_score: float = 0.95, account_like: str = "%") -> dict:
    """Scores every account active on ``as_of`` with the ACTIVE model and stores those at or above ``store_min_score``.

    Idempotent: rows of this model version and day are replaced.
    """
    artifact, version = load_active(conn, cfg)
    df = F.extract(conn, cfg.mst, as_of, account_like)
    scored = score_frame(df, artifact, explain_min=store_min_score)
    keep = scored[scored["score"] >= store_min_score]
    aml = sql.Identifier(cfg.aml)
    with conn.transaction():
        conn.execute(sql.SQL("DELETE FROM {a}.ml_score WHERE model_version = %s AND as_of_date = %s").format(a=aml), (version, as_of))
        with conn.cursor() as cur:
            cur.executemany(sql.SQL("INSERT INTO {a}.ml_score (model_version, as_of_date, account_id, product_type, score, rank_in_product, explanation)"
                                    " VALUES (%s, %s, %s, %s, %s, %s, %s::jsonb)").format(a=aml),
                            [(version, as_of, r.account_id, r.product_type, round(float(r.score), 6), int(r.rank_in_product), json.dumps(r.explanation))
                             for r in keep.itertuples()])
    return {"model_version": version, "as_of": as_of, "accounts_scored": int(len(scored)), "stored": int(len(keep)),
            "unscored_products": sorted(set(df["product_type"]) - set(artifact["products"]))}
