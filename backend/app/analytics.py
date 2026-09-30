"""Spending analysis over the local database.

Spend means what left your wallet: receipt totals (tax included, returns
netted) plus non-cancelled online order totals. Gas and car wash are
reported as a single figure and kept out of everything else.
"""

import sqlite3
from datetime import date, timedelta

from app import config, db

CANCELLED = "LOWER(COALESCE(o.status, '')) NOT LIKE '%cancel%'"

# Net paid per warehouse line, shelf price minus instant savings.
NET = "(ri.amount + ri.discount)"


def _range(start: str | None, end: str | None) -> tuple[str, str]:
    return start or "0000-01-01", end or "9999-12-31"


def _item_name_sql(alias: str) -> str:
    return f"COALESCE(p.name, {alias}.description)"


def summary(conn: sqlite3.Connection, start: str | None, end: str | None) -> dict:
    lo, hi = _range(start, end)
    wh = conn.execute(
        "SELECT COALESCE(SUM(total), 0) AS spend, "
        "COALESCE(SUM(CASE WHEN total > 0 THEN 1 ELSE 0 END), 0) AS trips "
        "FROM receipts WHERE channel = 'warehouse' AND txn_date BETWEEN ? AND ?",
        (lo, hi),
    ).fetchone()
    online = conn.execute(
        f"SELECT COALESCE(SUM(o.total), 0) AS spend, COUNT(*) AS orders FROM online_orders o "
        f"WHERE {CANCELLED} AND o.order_date BETWEEN ? AND ?",
        (lo, hi),
    ).fetchone()
    gas = conn.execute(
        "SELECT COALESCE(SUM(total), 0) FROM receipts WHERE channel = 'gas' AND txn_date BETWEEN ? AND ?",
        (lo, hi),
    ).fetchone()[0]
    items = conn.execute(
        "SELECT COALESCE(SUM(ri.units), 0) AS units, COUNT(DISTINCT ri.item_number) AS unique_items "
        "FROM receipt_items ri JOIN receipts r USING (barcode) "
        "WHERE r.channel = 'warehouse' AND ri.is_gas = 0 AND ri.units > 0 AND r.total > 0 "
        "AND r.txn_date BETWEEN ? AND ?",
        (lo, hi),
    ).fetchone()
    coverage = conn.execute(
        "SELECT MIN(d), MAX(d) FROM (SELECT txn_date AS d FROM receipts UNION ALL "
        "SELECT order_date FROM online_orders)"
    ).fetchone()

    total = wh["spend"] + online["spend"]
    return {
        "start": start,
        "end": end,
        "total_spend": round(total, 2),
        "warehouse_spend": round(wh["spend"], 2),
        "online_spend": round(online["spend"], 2),
        "gas_spend": round(gas, 2),
        "trips": wh["trips"],
        "online_orders": online["orders"],
        "avg_per_trip": round(wh["spend"] / wh["trips"], 2) if wh["trips"] else 0,
        "units": items["units"],
        "unique_items": items["unique_items"],
        "data_from": coverage[0],
        "data_to": coverage[1],
    }


def monthly(conn: sqlite3.Connection, start: str | None, end: str | None) -> list[dict]:
    lo, hi = _range(start, end)
    months: dict[str, dict] = {}

    def bucket(m: str) -> dict:
        return months.setdefault(m, {"month": m, "warehouse": 0.0, "online": 0.0, "trips": 0})

    for r in conn.execute(
        "SELECT substr(txn_date, 1, 7) AS m, SUM(total) AS spend, "
        "SUM(CASE WHEN total > 0 THEN 1 ELSE 0 END) AS trips FROM receipts "
        "WHERE channel = 'warehouse' AND txn_date BETWEEN ? AND ? GROUP BY m",
        (lo, hi),
    ):
        b = bucket(r["m"])
        b["warehouse"] = round(r["spend"], 2)
        b["trips"] = r["trips"]
    for r in conn.execute(
        f"SELECT substr(o.order_date, 1, 7) AS m, SUM(o.total) AS spend FROM online_orders o "
        f"WHERE {CANCELLED} AND o.order_date BETWEEN ? AND ? GROUP BY m",
        (lo, hi),
    ):
        bucket(r["m"])["online"] = round(r["spend"], 2)

    out = sorted(months.values(), key=lambda b: b["month"])
    for b in out:
        b["total"] = round(b["warehouse"] + b["online"], 2)
    return out


def categories(conn: sqlite3.Connection, start: str | None, end: str | None) -> list[dict]:
    """Spend by Costco department number. Costco doesn't publish department
    names, so each gets a user-editable label and sample items to identify it."""
    lo, hi = _range(start, end)
    rows = conn.execute(
        f"SELECT ri.department AS number, d.label, SUM({NET}) AS spend, COUNT(*) AS lines "
        "FROM receipt_items ri JOIN receipts r USING (barcode) "
        "LEFT JOIN departments d ON d.number = ri.department "
        "WHERE r.channel = 'warehouse' AND ri.is_gas = 0 AND r.txn_date BETWEEN ? AND ? "
        "GROUP BY ri.department ORDER BY spend DESC",
        (lo, hi),
    ).fetchall()
    out = []
    for r in rows:
        samples = [
            s["name"] for s in conn.execute(
                f"SELECT {_item_name_sql('ri')} AS name, SUM({NET}) AS spend FROM receipt_items ri "
                "JOIN receipts rc USING (barcode) LEFT JOIN products p ON p.item_number = ri.item_number "
                "WHERE ri.department IS ? AND ri.is_gas = 0 AND ri.units > 0 AND rc.txn_date BETWEEN ? AND ? "
                "GROUP BY ri.item_number ORDER BY spend DESC LIMIT 3",
                (r["number"], lo, hi),
            )
        ]
        out.append({
            "department": r["number"],
            "label": r["label"] or (f"Dept {r['number']}" if r["number"] is not None else "Uncategorized"),
            "labeled": r["label"] is not None,
            "spend": round(r["spend"], 2),
            "samples": samples,
        })

    online = conn.execute(
        f"SELECT COALESCE(SUM(o.total), 0) FROM online_orders o WHERE {CANCELLED} AND o.order_date BETWEEN ? AND ?",
        (lo, hi),
    ).fetchone()[0]
    if online:
        out.append({"department": None, "label": "Costco.com orders", "labeled": True,
                    "spend": round(online, 2), "samples": []})
    out.sort(key=lambda c: c["spend"], reverse=True)
    return out


def _item_stats(conn: sqlite3.Connection, lo: str, hi: str) -> list[dict]:
    """Per-item totals across warehouse and online purchases (returns netted)."""
    rows = conn.execute(
        f"""
        SELECT item_number, MAX(name) AS name, SUM(spend) AS spend, SUM(units) AS units,
               SUM(purchases) AS purchases
        FROM (
            SELECT ri.item_number, {_item_name_sql('ri')} AS name, {NET} AS spend, ri.units AS units,
                   CASE WHEN ri.units > 0 THEN 1 ELSE 0 END AS purchases
            FROM receipt_items ri JOIN receipts r USING (barcode)
            LEFT JOIN products p ON p.item_number = ri.item_number
            WHERE r.channel = 'warehouse' AND ri.is_gas = 0 AND ri.description != 'Instant savings'
              AND r.txn_date BETWEEN :lo AND :hi
            UNION ALL
            SELECT oi.item_number, {_item_name_sql('oi')}, COALESCE(oi.amount, 0), COALESCE(oi.quantity, 0), 1
            FROM online_items oi JOIN online_orders o USING (order_number)
            LEFT JOIN products p ON p.item_number = oi.item_number
            WHERE {CANCELLED} AND oi.item_number IS NOT NULL AND o.order_date BETWEEN :lo AND :hi
        )
        GROUP BY item_number
        """,
        {"lo": lo, "hi": hi},
    ).fetchall()
    return [
        {
            "item_number": r["item_number"],
            "name": r["name"],
            "spend": round(r["spend"], 2),
            "units": r["units"],
            "purchases": r["purchases"],
            "avg_unit_price": round(r["spend"] / r["units"], 2) if r["units"] > 0 else None,
        }
        for r in rows
    ]


def price_history(conn: sqlite3.Connection, item_number: str) -> list[dict]:
    """Shelf unit price at each warehouse purchase, oldest first."""
    return [
        dict(r) for r in conn.execute(
            "SELECT r.txn_date AS date, r.warehouse_name AS warehouse, ri.unit_price, ri.units, "
            f"ri.discount, {NET} AS paid, r.barcode FROM receipt_items ri JOIN receipts r USING (barcode) "
            "WHERE ri.item_number = ? AND ri.units > 0 AND r.channel = 'warehouse' "
            "ORDER BY r.txn_datetime, r.txn_date",
            (item_number,),
        )
    ]


def price_changes(conn: sqlite3.Connection, lo: str, hi: str) -> list[dict]:
    """Items whose shelf price moved between their first and latest purchase."""
    rows = conn.execute(
        """
        WITH buys AS (
            SELECT ri.item_number, ri.unit_price, r.txn_date,
                   ROW_NUMBER() OVER (PARTITION BY ri.item_number ORDER BY r.txn_date, r.txn_datetime) AS first_rank,
                   ROW_NUMBER() OVER (PARTITION BY ri.item_number ORDER BY r.txn_date DESC, r.txn_datetime DESC) AS last_rank
            FROM receipt_items ri JOIN receipts r USING (barcode)
            WHERE r.channel = 'warehouse' AND ri.is_gas = 0 AND ri.units > 0 AND ri.unit_price > 0
              AND r.txn_date BETWEEN ? AND ?
        )
        SELECT f.item_number, f.unit_price AS first_price, f.txn_date AS first_date,
               l.unit_price AS last_price, l.txn_date AS last_date
        FROM buys f JOIN buys l ON l.item_number = f.item_number AND l.last_rank = 1
        WHERE f.first_rank = 1 AND f.txn_date < l.txn_date AND ABS(l.unit_price - f.unit_price) >= 0.01
        """,
        (lo, hi),
    ).fetchall()
    names = _names(conn, [r["item_number"] for r in rows])
    out = []
    for r in rows:
        change = r["last_price"] - r["first_price"]
        out.append({
            **dict(r),
            "name": names.get(r["item_number"]),
            "change": round(change, 2),
            "change_pct": round(100 * change / r["first_price"], 1),
        })
    out.sort(key=lambda x: x["change_pct"], reverse=True)
    return out


def _names(conn: sqlite3.Connection, item_numbers: list[str]) -> dict[str, str]:
    if not item_numbers:
        return {}
    marks = ",".join("?" * len(item_numbers))
    rows = conn.execute(
        f"SELECT ri.item_number, COALESCE(MAX(p.name), MAX(ri.description)) AS name FROM receipt_items ri "
        f"LEFT JOIN products p ON p.item_number = ri.item_number WHERE ri.item_number IN ({marks}) "
        "GROUP BY ri.item_number",
        item_numbers,
    ).fetchall()
    return {r["item_number"]: r["name"] for r in rows}


def insights(conn: sqlite3.Connection, start: str | None, end: str | None, limit: int = 10) -> dict:
    lo, hi = _range(start, end)
    stats = _item_stats(conn, lo, hi)
    bought = [s for s in stats if s["units"] > 0]
    changes = price_changes(conn, lo, hi)
    return {
        "top_spent": sorted(bought, key=lambda s: s["spend"], reverse=True)[:limit],
        "most_purchased": sorted(bought, key=lambda s: (s["purchases"], s["units"]), reverse=True)[:limit],
        "most_expensive": sorted(
            (s for s in bought if s["avg_unit_price"]), key=lambda s: s["avg_unit_price"], reverse=True
        )[:limit],
        "price_increases": [c for c in changes if c["change"] > 0][:limit],
        "price_decreases": sorted((c for c in changes if c["change"] < 0), key=lambda c: c["change_pct"])[:limit],
    }


def price_adjustments(conn: sqlite3.Connection, today: date) -> list[dict]:
    """Recent purchases that were later sold for less within the adjustment window.

    The signal is our own receipts: the same item rung up cheaper (lower shelf
    price or instant savings) after you bought it. Costco refunds the
    difference if you ask within the window.
    """
    window = config.PRICE_ADJUST_DAYS
    since = (today - timedelta(days=window)).isoformat()
    rows = conn.execute(
        f"""
        SELECT paid.barcode, paid.line, paid.item_number, paid.units, paid.txn_date AS bought_on,
               paid.warehouse_name, paid.unit_net AS paid_unit,
               later.unit_net AS best_unit, later.txn_date AS seen_on
        FROM (
            SELECT ri.barcode, ri.line, ri.item_number, ri.units, r.txn_date, r.warehouse_name,
                   {NET} / ri.units AS unit_net
            FROM receipt_items ri JOIN receipts r USING (barcode)
            WHERE r.channel = 'warehouse' AND ri.is_gas = 0 AND ri.units > 0 AND r.total > 0
              AND r.txn_date >= :since
        ) paid
        JOIN (
            SELECT ri.item_number, r.txn_date, {NET} / ri.units AS unit_net
            FROM receipt_items ri JOIN receipts r USING (barcode)
            WHERE r.channel = 'warehouse' AND ri.units > 0 AND r.total > 0
        ) later
          ON later.item_number = paid.item_number
         AND later.txn_date > paid.txn_date
         AND julianday(later.txn_date) - julianday(paid.txn_date) <= :window
         AND later.unit_net < paid.unit_net - 0.009
        """,
        {"since": since, "window": window},
    ).fetchall()

    best: dict[tuple, dict] = {}
    for r in rows:
        key = (r["barcode"], r["line"])
        if key not in best or r["best_unit"] < best[key]["best_unit"]:
            best[key] = dict(r)
    names = _names(conn, list({b["item_number"] for b in best.values()}))
    out = []
    for b in best.values():
        deadline = date.fromisoformat(b["bought_on"]) + timedelta(days=window)
        out.append({
            "barcode": b["barcode"],
            "line": b["line"],
            "item_number": b["item_number"],
            "name": names.get(b["item_number"]),
            "bought_on": b["bought_on"],
            "warehouse": b["warehouse_name"],
            "paid_unit": round(b["paid_unit"], 2),
            "lower_unit": round(b["best_unit"], 2),
            "seen_on": b["seen_on"],
            "units": b["units"],
            "refund_estimate": round((b["paid_unit"] - b["best_unit"]) * b["units"], 2),
            "claim_by": deadline.isoformat(),
        })
    out.sort(key=lambda a: a["claim_by"])
    return out


def record_price_drop_events(conn: sqlite3.Connection, today: date) -> None:
    for a in price_adjustments(conn, today):
        db.add_event(
            conn, "price_drop", f"{a['barcode']}:{a['line']}:{a['lower_unit']}",
            f"Price adjustment: {a['name'] or a['item_number']}",
            f"You paid ${a['paid_unit']:.2f} on {a['bought_on']}; it rang up at ${a['lower_unit']:.2f} "
            f"on {a['seen_on']}. Ask for about ${a['refund_estimate']:.2f} back by {a['claim_by']}.",
        )


def executive(conn: sqlite3.Connection, today: date) -> dict:
    """Would an Executive membership pay for itself over the last 12 months?"""
    since = (today - timedelta(days=365)).isoformat()
    warehouse = conn.execute(
        f"SELECT COALESCE(SUM({NET}), 0) FROM receipt_items ri JOIN receipts r USING (barcode) "
        "WHERE r.channel = 'warehouse' AND ri.is_gas = 0 AND r.txn_date >= ?",
        (since,),
    ).fetchone()[0]
    online = conn.execute(
        f"SELECT COALESCE(SUM(COALESCE(o.merchandise_total, o.total)), 0) FROM online_orders o "
        f"WHERE {CANCELLED} AND o.order_date >= ?",
        (since,),
    ).fetchone()[0]
    eligible = warehouse + online
    reward = min(eligible * config.EXEC_REWARD_RATE, config.EXEC_REWARD_CAP)
    return {
        "period_start": since,
        "eligible_spend": round(eligible, 2),
        "reward": round(reward, 2),
        "upgrade_cost": config.EXEC_UPGRADE_COST,
        "net": round(reward - config.EXEC_UPGRADE_COST, 2),
        "break_even_spend": round(config.EXEC_UPGRADE_COST / config.EXEC_REWARD_RATE, 2),
        "worth_it": reward > config.EXEC_UPGRADE_COST,
        "note": "Pre-tax merchandise, excluding gas. Costco also excludes tobacco, postage and some "
                "services, which receipts don't flag, so treat this as an upper bound.",
    }


def export_rows(conn: sqlite3.Connection, start: str | None, end: str | None):
    lo, hi = _range(start, end)
    yield ["date", "channel", "location", "receipt_or_order", "item_number", "item", "department",
           "units", "unit_price", "amount", "instant_savings", "net"]
    for r in conn.execute(
        f"SELECT r.txn_date, r.channel, r.warehouse_name, r.barcode, ri.item_number, "
        f"{_item_name_sql('ri')} AS name, COALESCE(d.label, ri.department) AS dept, ri.units, ri.unit_price, "
        f"ri.amount, ri.discount, {NET} AS net FROM receipt_items ri JOIN receipts r USING (barcode) "
        "LEFT JOIN products p ON p.item_number = ri.item_number "
        "LEFT JOIN departments d ON d.number = ri.department "
        "WHERE r.txn_date BETWEEN ? AND ? ORDER BY r.txn_date, r.barcode, ri.line",
        (lo, hi),
    ):
        yield list(r)
    for r in conn.execute(
        f"SELECT o.order_date, 'online', 'Costco.com', o.order_number, oi.item_number, "
        f"{_item_name_sql('oi')}, '', oi.quantity, oi.unit_price, oi.amount, 0, oi.amount "
        "FROM online_items oi JOIN online_orders o USING (order_number) "
        "LEFT JOIN products p ON p.item_number = oi.item_number "
        f"WHERE {CANCELLED} AND o.order_date BETWEEN ? AND ? ORDER BY o.order_date, o.order_number, oi.line",
        (lo, hi),
    ):
        yield list(r)
