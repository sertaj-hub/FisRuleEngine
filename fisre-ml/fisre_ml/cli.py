"""fisre-ml train | score | evaluate"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import date

from .config import Config


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="fisre-ml", description=__doc__)
    sub = p.add_subparsers(dest="cmd", required=True)
    t = sub.add_parser("train", help="train on the history ending at --to and register the model as ACTIVE")
    t.add_argument("--to", type=date.fromisoformat, required=True, help="last posting day to train on (YYYY-MM-DD)")
    t.add_argument("--history-days", type=int, default=90)
    t.add_argument("--max-days", type=int, default=30, help="posting days sampled from the history")
    s = sub.add_parser("score", help="score one posting day with the ACTIVE model")
    s.add_argument("--as-of", type=date.fromisoformat, required=True, help="posting day (business date minus the posting offset)")
    s.add_argument("--store-min-score", type=float, default=0.95)
    sub.add_parser("evaluate", help="inject known patterns into a rolled-back synthetic world and report recall")
    a = p.parse_args(argv)

    cfg = Config.from_env()
    if a.cmd == "evaluate":
        from . import evaluate
        print(json.dumps(evaluate.run(cfg), indent=2))
        return 0
    with cfg.connect() as conn:
        if a.cmd == "train":
            from .model import train
            art = train(conn, cfg, a.to, a.history_days, a.max_days)
            print(json.dumps({"model_version": art["version"], "artifact": art["path"],
                              "products": {k: v["rows"] for k, v in art["products"].items()}}, indent=2))
        else:
            from .scoring import score_day
            print(json.dumps(score_day(conn, cfg, a.as_of, a.store_min_score), indent=2, default=str))
    return 0


if __name__ == "__main__":
    sys.exit(main())
