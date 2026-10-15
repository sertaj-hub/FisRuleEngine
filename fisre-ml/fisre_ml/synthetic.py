"""Synthetic world for the evaluation command and the tests (REQ-ML-012).

Ordinary accounts have their own rhythm; a few accounts get a known suspicious pattern on the last day. This proves the
pipeline finds what it should. It says nothing about accuracy on real customers.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, timedelta

import numpy as np
import psycopg
from psycopg import sql

PREFIX = "EVAL-"


@dataclass
class World:
    as_of: date
    injected: dict[str, str] = field(default_factory=dict)   # account_id -> pattern name
    accounts: int = 0


def _ensure_partitions(conn: psycopg.Connection, mst: str, days: list[date]) -> None:
    for d in days:
        conn.execute(sql.SQL("CREATE TABLE IF NOT EXISTS {m}.{p} PARTITION OF {m}.txn FOR VALUES FROM ({lo}) TO ({hi})")
                     .format(m=sql.Identifier(mst), p=sql.Identifier(f"txn_{d:%Y%m%d}"), lo=sql.Literal(d), hi=sql.Literal(d + timedelta(days=1))))


def build(conn: psycopg.Connection, mst: str, as_of: date, n_deposit: int = 400, n_card: int = 150, history: int = 45, seed: int = 11) -> World:
    """Inserts the world into ``mst`` (the caller rolls the transaction back)."""
    rng = np.random.default_rng(seed)
    days = [as_of - timedelta(days=i) for i in range(history, -1, -1)]
    _ensure_partitions(conn, mst, days)
    m = sql.Identifier(mst)
    accounts = [(f"{PREFIX}D{i:04d}", "DEPOSIT") for i in range(n_deposit)] + [(f"{PREFIX}C{i:04d}", "CARD") for i in range(n_card)]
    cust = [(f"{PREFIX}CUS{i:04d}",) for i in range(len(accounts))]
    with conn.cursor() as cur:
        cur.executemany(sql.SQL("INSERT INTO {m}.customer (customer_id, customer_type, full_name, batch_id) VALUES (%s, 'INDIVIDUAL', 'Synthetic', 'EVAL')").format(m=m), cust)
        cur.executemany(sql.SQL("INSERT INTO {m}.account (account_id, primary_customer_id, product_type, status, open_date, currency, batch_id)"
                                " VALUES (%s, %s, %s, 'ACTIVE', DATE '2015-01-01', 'USD', 'EVAL')").format(m=m),
                        [(a, c[0], p) for (a, p), c in zip(accounts, cust)])
    rows: list[tuple] = []
    seq = 0

    def txn(acct: str, d: date, ttype: str, direction: str, amount: float, country: str | None = None) -> None:
        nonlocal seq
        seq += 1
        rows.append((f"{PREFIX}T{seq:08d}", acct, f"{d} 12:00:00", d, ttype, direction, round(float(amount), 2), "USD", country))

    for acct, product in accounts:
        rate = rng.uniform(0.3, 2.0)                         # transactions per day
        size = rng.lognormal(mean=rng.uniform(3.5, 5.5), sigma=0.6)
        cash_prone = product == "DEPOSIT" and rng.random() < 0.3
        for d in days:
            for _ in range(rng.poisson(rate)):
                amount = max(5.0, rng.lognormal(np.log(size), 0.5))
                if product == "CARD":
                    txn(acct, d, rng.choice(["POS_PURCHASE", "ECOM_PURCHASE", "CARD_PAYMENT"], p=[.5, .35, .15]), "CREDIT" if rng.random() < .15 else "DEBIT", amount)
                elif cash_prone and rng.random() < 0.4:
                    txn(acct, d, "CASH_DEPOSIT", "CREDIT", min(amount, 2500))
                else:
                    ttype = rng.choice(["ACH_CREDIT", "ACH_DEBIT", "POS_PURCHASE", "CHECK_DEPOSIT"])
                    txn(acct, d, ttype, "CREDIT" if ttype in ("ACH_CREDIT", "CHECK_DEPOSIT") else "DEBIT", amount)
            if rng.random() < 0.9:                           # everybody is active most days so the as-of day always has candidates
                txn(acct, d, "ACH_DEBIT" if product == "DEPOSIT" else "POS_PURCHASE", "DEBIT", max(5.0, rng.lognormal(np.log(size), 0.4)))

    world = World(as_of=as_of, accounts=len(accounts))
    d0 = as_of
    for i in range(3):                                       # structuring: several cash deposits just under 10,000
        a = f"{PREFIX}D{i:04d}"
        for _ in range(4):
            txn(a, d0, "CASH_DEPOSIT", "CREDIT", rng.uniform(8500, 9900))
        world.injected[a] = "structuring"
    for i in range(3, 6):                                    # rapid movement: large wire in, nearly all out the same day
        a = f"{PREFIX}D{i:04d}"
        amt = rng.uniform(50000, 90000)
        txn(a, d0, "WIRE_IN", "CREDIT", amt, "US")
        txn(a, d0, "WIRE_OUT", "DEBIT", amt * 0.97, "US")
        world.injected[a] = "rapid_movement"
    for i in range(6, 9):                                    # many new foreign counterparties in one day
        a = f"{PREFIX}D{i:04d}"
        for c in ["RU", "IR", "KP", "SY", "VE", "MM"]:
            txn(a, d0, "WIRE_OUT", "DEBIT", rng.uniform(2000, 4000), c)
        world.injected[a] = "foreign_burst"
    for i in range(3):                                       # card cash-advance burst
        a = f"{PREFIX}C{i:04d}"
        for _ in range(6):
            txn(a, d0, "CARD_CASH_ADVANCE", "DEBIT", rng.uniform(400, 900))
        world.injected[a] = "cash_advance_burst"

    with conn.cursor() as cur:
        cur.executemany(sql.SQL("INSERT INTO {m}.txn (transaction_id, account_id, txn_ts, posting_date, txn_type, direction, amount, currency,"
                                " counterparty_country, batch_id) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, 'EVAL')").format(m=m), rows)
    return world
