from fisre_ml import evaluate, synthetic
from fisre_ml import features as F
from fisre_ml.scoring import score_frame
from tests.conftest import AS_OF


def test_evaluation_finds_most_injected_patterns_and_reports_honestly(world):  # REQ-ML-012
    cfg, conn, w, artifact = world
    scored = score_frame(F.extract(conn, cfg.mst, AS_OF, synthetic.PREFIX + "%"), artifact)
    m = evaluate.metrics(scored, w.injected)
    assert m["recall_at_k"] >= 0.6                    # synthetic patterns are blatant; this guards against a broken pipeline
    assert {"structuring", "rapid_movement", "foreign_burst", "cash_advance_burst"} == set(m["per_pattern"])
    assert "not accuracy on real customers" in m["note"]
