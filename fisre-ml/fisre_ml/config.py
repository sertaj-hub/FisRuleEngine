"""Connection and settings from the same environment variables the Java engine uses."""
from __future__ import annotations

import os
import re
from dataclasses import dataclass
from pathlib import Path

import psycopg

_IDENT = re.compile(r"^[a-z_][a-z0-9_]{0,62}$")
_URL = re.compile(r"^jdbc:postgresql://([^:/]+)(?::(\d+))?/([^?]+)")


def _ident(name: str, what: str) -> str:
    if not _IDENT.match(name):
        raise ValueError(f"{what} '{name}' is not a valid schema name")
    return name


@dataclass(frozen=True)
class Config:
    host: str
    port: int
    dbname: str
    user: str
    password: str
    mst: str = "mst"
    aml: str = "aml"
    model_name: str = "account_anomaly"
    model_dir: Path = Path("models")

    @staticmethod
    def from_env() -> "Config":
        url = os.environ.get("FISRE_DB_URL", "jdbc:postgresql://localhost:5432/fisre")
        m = _URL.match(url)
        if not m:
            raise ValueError("FISRE_DB_URL must look like jdbc:postgresql://host:port/database")
        password = os.environ.get("FISRE_DB_PASSWORD", "fisre")
        if password == "fisre" and os.environ.get("FISRE_ALLOW_DEFAULT_CREDENTIALS", "false").lower() != "true":
            # same rule as the Java engine (ADR-0007)
            raise ValueError("refusing the default database password; set FISRE_DB_PASSWORD "
                             "(FISRE_ALLOW_DEFAULT_CREDENTIALS=true is for local development only)")
        return Config(
            host=m.group(1), port=int(m.group(2) or 5432), dbname=m.group(3),
            user=os.environ.get("FISRE_DB_USER", "fisre"), password=password,
            mst=_ident(os.environ.get("FISRE_SCHEMA_MST", "mst"), "FISRE_SCHEMA_MST"),
            aml=_ident(os.environ.get("FISRE_SCHEMA_AML", "aml"), "FISRE_SCHEMA_AML"),
            model_name=os.environ.get("FISRE_ML_MODEL_NAME", "account_anomaly"),
            model_dir=Path(os.environ.get("FISRE_ML_MODEL_DIR", "models")),
        )

    def connect(self) -> psycopg.Connection:
        conn = psycopg.connect(host=self.host, port=self.port, dbname=self.dbname, user=self.user, password=self.password)
        conn.execute("SET statement_timeout = '1h'")
        return conn
