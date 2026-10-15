from datetime import date, timedelta

from psycopg import sql

from fisre_ml import features as F
from fisre_ml import synthetic
from tests.conftest import AS_OF

LIKE = synthetic.PREFIX + "%"


def _seed(conn, mst):
    m = sql.Identifier(mst)
    synthetic._ensure_partitions(conn, mst, [AS_OF - timedelta(days=i) for i in range(0, 8)] + [AS_OF + timedelta(days=1)])
    conn.execute(sql.SQL("INSERT INTO {m}.customer (customer_id, customer_type, full_name, batch_id) VALUES ('EVAL-FC', 'INDIVIDUAL', 'F', 'EVAL')").format(m=m))
    for a in ("EVAL-F1", "EVAL-F2", "EVAL-F3"):
        conn.execute(sql.SQL("INSERT INTO {m}.account (account_id, primary_customer_id, product_type, status, open_date, currency, batch_id)"
                             " VALUES (%s, 'EVAL-FC', 'DEPOSIT', 'ACTIVE', DATE '2015-01-01', 'USD', 'EVAL')").format(m=m), (a,))
    n = [0]

    def t(acct, d, ttype, direction, amount, country=None):
        n[0] += 1
        conn.execute(sql.SQL("INSERT INTO {m}.txn (transaction_id, account_id, txn_ts, posting_date, txn_type, direction, amount, currency,"
                             " counterparty_country, batch_id) VALUES (%s, %s, %s, %s, %s, %s, %s, 'USD', %s, 'EVAL')").format(m=m),
                     (f"EVAL-FT{n[0]}", acct, f"{d} 12:00:00", d, ttype, direction, amount, country))
    return t


def test_features_are_computed_per_active_account(conn, cfg):  # REQ-ML-001
    t = _seed(conn, cfg.mst)
    t("EVAL-F1", AS_OF, "CASH_DEPOSIT", "CREDIT", 9500)
    t("EVAL-F1", AS_OF, "CASH_DEPOSIT", "CREDIT", 9000)
    t("EVAL-F1", AS_OF - timedelta(days=2), "WIRE_OUT", "DEBIT", 2000, "RU")
    t("EVAL-F2", AS_OF - timedelta(days=1), "ACH_CREDIT", "CREDIT", 100)       # active yesterday only: not a candidate today
    t("EVAL-F3", AS_OF, "ACH_CREDIT", "CREDIT", 100)

    df = F.extract(conn, cfg.mst, AS_OF, LIKE)

    assert list(df["account_id"]) == ["EVAL-F1", "EVAL-F3"]
    f1 = df.set_index("account_id").loc["EVAL-F1"]
    assert f1["txn_count_1d"] == 2 and f1["cash_count_1d"] == 2 and f1["cash_total_1d"] == 18500
    assert f1["txn_count_7d"] == 3 and f1["near_threshold_cash_7d"] == 2
    assert f1["distinct_cpty_countries_7d"] == 1
    assert 0 < f1["flow_through_7d"] < 1            # 2,000 out against 18,500 in
    assert set(F.FEATURES) <= set(df.columns)


def test_features_are_point_in_time(conn, cfg):  # REQ-ML-002
    t = _seed(conn, cfg.mst)
    t("EVAL-F1", AS_OF, "ACH_CREDIT", "CREDIT", 100)
    before = F.extract(conn, cfg.mst, AS_OF, LIKE)

    t("EVAL-F1", AS_OF + timedelta(days=1), "WIRE_IN", "CREDIT", 999999, "RU")   # posted after the as-of day
    after = F.extract(conn, cfg.mst, AS_OF, LIKE)

    assert before.equals(after)
