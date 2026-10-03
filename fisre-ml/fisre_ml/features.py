"""Features per account for one posting day, computed with set-based SQL (REQ-ML-001, REQ-ML-002).

Only accounts with a transaction on the as-of day are returned (the same candidate pruning the rules use). Everything is
read with posting_date <= as-of, so the result is point in time.
"""
from __future__ import annotations

from datetime import date, timedelta

import numpy as np
import pandas as pd
import psycopg
from psycopg import sql

# Raw feature columns, in model order. LOGGED ones are heavy-tailed amounts/counts and are log1p-transformed for the model.
FEATURES = [
    "txn_count_1d", "total_1d", "credit_total_1d", "debit_total_1d", "cash_total_1d", "cash_count_1d", "max_amount_1d",
    "txn_count_7d", "total_7d", "distinct_cpty_countries_7d", "foreign_share_7d", "flow_through_7d",
    "near_threshold_cash_7d", "round_share_7d", "ratio_to_baseline",
]
SHARES = {"foreign_share_7d", "flow_through_7d", "round_share_7d"}   # already in [0,1]; not logged

_QUERY = sql.SQL("""
WITH day AS (
    SELECT t.account_id,
           COUNT(*)                                                                AS txn_count_1d,
           SUM(t.amount)                                                           AS total_1d,
           COALESCE(SUM(CASE WHEN t.direction = 'CREDIT' THEN t.amount END), 0)    AS credit_total_1d,
           COALESCE(SUM(CASE WHEN t.direction = 'DEBIT'  THEN t.amount END), 0)    AS debit_total_1d,
           COALESCE(SUM(CASE WHEN r.is_cash = 'Y' THEN t.amount END), 0)           AS cash_total_1d,
           COUNT(CASE WHEN r.is_cash = 'Y' THEN 1 END)                             AS cash_count_1d,
           MAX(t.amount)                                                           AS max_amount_1d
    FROM {mst}.txn t JOIN {mst}.ref_txn_type r ON r.txn_type = t.txn_type
    WHERE t.posting_date = %(d)s AND t.account_id LIKE %(like)s
    GROUP BY t.account_id
), win AS (
    SELECT t.account_id,
           COUNT(*)                                                                AS txn_count_7d,
           SUM(t.amount)                                                           AS total_7d,
           COUNT(DISTINCT CASE WHEN t.counterparty_country IS NOT NULL AND t.counterparty_country <> 'US'
                               THEN t.counterparty_country END)                    AS distinct_cpty_countries_7d,
           AVG(CASE WHEN t.counterparty_country IS NOT NULL AND t.counterparty_country <> 'US' THEN 1.0 ELSE 0.0 END) AS foreign_share_7d,
           COALESCE(SUM(CASE WHEN t.direction = 'CREDIT' THEN t.amount END), 0)    AS in_7d,
           COALESCE(SUM(CASE WHEN t.direction = 'DEBIT'  THEN t.amount END), 0)    AS out_7d,
           COUNT(CASE WHEN r.is_cash = 'Y' AND t.amount >= 8000 AND t.amount < 10000 THEN 1 END) AS near_threshold_cash_7d,
           AVG(CASE WHEN t.amount >= 1000 AND MOD(t.amount, 1000) = 0 THEN 1.0 ELSE 0.0 END)    AS round_share_7d
    FROM {mst}.txn t JOIN {mst}.ref_txn_type r ON r.txn_type = t.txn_type
    WHERE t.posting_date BETWEEN %(d6)s AND %(d)s AND t.account_id IN (SELECT account_id FROM day)
    GROUP BY t.account_id
), base AS (
    SELECT t.account_id, SUM(t.amount) / 30.0 AS avg_daily_30d
    FROM {mst}.txn t
    WHERE t.posting_date BETWEEN %(d30)s AND %(d1)s AND t.account_id IN (SELECT account_id FROM day)
    GROUP BY t.account_id
)
SELECT a.account_id, a.product_type,
       day.txn_count_1d, day.total_1d, day.credit_total_1d, day.debit_total_1d, day.cash_total_1d, day.cash_count_1d, day.max_amount_1d,
       win.txn_count_7d, win.total_7d, win.distinct_cpty_countries_7d, win.foreign_share_7d,
       CASE WHEN GREATEST(win.in_7d, win.out_7d) > 0 THEN LEAST(win.in_7d, win.out_7d) / GREATEST(win.in_7d, win.out_7d) ELSE 0 END AS flow_through_7d,
       win.near_threshold_cash_7d, win.round_share_7d,
       day.total_1d / (COALESCE(base.avg_daily_30d, 0) + 1) AS ratio_to_baseline
FROM day
JOIN win ON win.account_id = day.account_id
JOIN {mst}.account a ON a.account_id = day.account_id
LEFT JOIN base ON base.account_id = day.account_id
ORDER BY a.account_id
""")


def extract(conn: psycopg.Connection, mst: str, as_of: date, account_like: str = "%") -> pd.DataFrame:
    """One row per account active on ``as_of``: account_id, product_type and the raw FEATURES."""
    params = {"d": as_of, "d6": as_of - timedelta(days=6), "d30": as_of - timedelta(days=30), "d1": as_of - timedelta(days=1),
              "like": account_like}
    with conn.cursor() as cur:
        cur.execute(_QUERY.format(mst=sql.Identifier(mst)), params)
        cols = [c.name for c in cur.description]
        df = pd.DataFrame(cur.fetchall(), columns=cols)
    for c in FEATURES:
        df[c] = pd.to_numeric(df[c]).astype(float)
    return df


def transform(df: pd.DataFrame) -> np.ndarray:
    """Model input: log1p on amounts and counts, shares left as they are."""
    cols = []
    for c in FEATURES:
        v = df[c].to_numpy(dtype=float)
        cols.append(v if c in SHARES else np.log1p(np.maximum(v, 0.0)))
    return np.column_stack(cols)


def untransform_value(feature: str, v: float) -> float:
    return v if feature in SHARES else float(np.expm1(v))
