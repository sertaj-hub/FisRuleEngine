from datetime import date

from psycopg import sql

from fisre_ml import evaluate


# Own module on purpose: the shared `world` fixture holds uncommitted partitions that would block a second world.
def test_evaluation_leaves_no_data_behind(cfg):  # REQ-ML-012
    evaluate.run(cfg, as_of=date(2026, 9, 30))
    with cfg.connect() as c, c.cursor() as cur:
        cur.execute(sql.SQL("SELECT COUNT(*) FROM {m}.account WHERE account_id LIKE 'EVAL-%'").format(m=sql.Identifier(cfg.mst)))
        assert cur.fetchone()[0] == 0
