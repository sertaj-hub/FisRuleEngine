"""Evaluation on injected patterns (REQ-ML-012). Runs inside a transaction that is always rolled back."""
from __future__ import annotations

import tempfile
from dataclasses import replace
from datetime import date, timedelta
from pathlib import Path

from . import features as F
from . import synthetic
from .config import Config
from .model import train
from .scoring import score_frame


def run(cfg: Config, as_of: date = date(2026, 9, 30), seed: int = 11) -> dict:
    with cfg.connect() as conn:
        try:
            world = synthetic.build(conn, cfg.mst, as_of, seed=seed)
            with tempfile.TemporaryDirectory() as tmp:
                # trains on the days before the as-of day only (no peeking), on the synthetic accounts only, without registering
                local = replace(cfg, model_dir=Path(tmp))
                artifact = train(conn, local, as_of - timedelta(days=1), history_days=45, max_days=30,
                                 account_like=synthetic.PREFIX + "%", register=False)
            df = F.extract(conn, cfg.mst, as_of, synthetic.PREFIX + "%")
            scored = score_frame(df, artifact, explain_min=0.0)
        finally:
            conn.rollback()
    return metrics(scored, world.injected)


def metrics(scored, injected: dict[str, str]) -> dict:
    """Recall and precision when the k most unusual accounts (k = number injected) are flagged, overall and per pattern."""
    ranked = scored.sort_values(["score"], ascending=False, kind="stable")
    rank_by_account = dict(zip(scored["account_id"], scored["rank_in_product"]))
    k = len(injected)
    top = list(ranked["account_id"].head(k))
    hits = [a for a in top if a in injected]
    flagged_any = set(ranked[ranked["score"] >= 0.99]["account_id"])
    per_pattern = {}
    for pattern in sorted(set(injected.values())):
        members = [a for a, p in injected.items() if p == pattern]
        per_pattern[pattern] = {"injected": len(members), "in_top_k": sum(1 for a in members if a in top), "score_ge_0_99": sum(1 for a in members if a in flagged_any),
                              "rank_in_product": sorted(int(rank_by_account[a]) for a in members)}
    return {"accounts": int(len(scored)), "injected": k, "top_k": k, "recall_at_k": round(len(hits) / k, 3), "precision_at_k": round(len(hits) / k, 3),
            "flagged_at_0_99": len(flagged_any), "false_flags_at_0_99": len(flagged_any - set(injected)), "per_pattern": per_pattern,
            "note": "synthetic data: proves the pipeline, not accuracy on real customers"}
