import os
from datetime import date
from pathlib import Path

import pytest

# Same variables as the Java engine; local-development defaults so `pytest` works against the docker-compose database.
os.environ.setdefault("FISRE_DB_URL", "jdbc:postgresql://localhost:5432/fisre")
os.environ.setdefault("FISRE_DB_USER", "fisre")
os.environ.setdefault("FISRE_DB_PASSWORD", "fisre")
os.environ.setdefault("FISRE_ALLOW_DEFAULT_CREDENTIALS", "true")

from fisre_ml import synthetic  # noqa: E402
from fisre_ml.config import Config  # noqa: E402

AS_OF = date(2026, 9, 30)


@pytest.fixture
def cfg(tmp_path: Path) -> Config:
    from dataclasses import replace
    return replace(Config.from_env(), model_dir=tmp_path)


@pytest.fixture
def conn(cfg):
    """A connection whose work is always rolled back: tests never leave data behind."""
    c = cfg.connect()
    yield c
    c.rollback()
    c.close()


@pytest.fixture(scope="module")
def world(tmp_path_factory):
    """One synthetic world with a registered model, shared by the tests of a module and rolled back at the end."""
    from dataclasses import replace
    from datetime import timedelta
    from fisre_ml.model import train

    cfg = replace(Config.from_env(), model_dir=tmp_path_factory.mktemp("models"))
    c = cfg.connect()
    w = synthetic.build(c, cfg.mst, AS_OF)
    artifact = train(c, cfg, AS_OF - timedelta(days=1), history_days=45, max_days=30, account_like=synthetic.PREFIX + "%")
    yield cfg, c, w, artifact
    c.rollback()
    c.close()
