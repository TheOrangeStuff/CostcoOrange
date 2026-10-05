"""Spending analysis over the local database.

Spend means what left your wallet: receipt totals (tax included, returns
netted) plus non-cancelled online order totals. Gas and car wash are
reported as a single figure and kept out of everything else.
"""

import sqlite3
import statistics
from collections import defaultdict
from datetime import date, timedelta

from app import config, db

CANCELLED = "LOWER(COALESCE(o.status, '')) NOT LIKE '%cancel%'"

# Net paid per warehouse line, shelf price minus instant savings.
NET = "(ri.amount + ri.discount)"

# Every line that was bought (not returned), warehouse and online, as
# (item_number, d, units, spend, unit_price, src).
BOUGHT_LINES = f"""
    SELECT ri.item_number, r.txn_date AS d, ri.units, {NET} AS spend, ri.unit_price, 'warehouse' AS src
    FROM receipt_items ri JOIN receipts r USING (barcode)
    WHERE r.channel = 'warehouse' AND ri.is_gas = 0 AND ri.units > 0 AND r.total > 0
    UNION ALL
    SELECT oi.item_number, o.order_date, COALESCE(oi.quantity, 1), COALESCE(oi.amount, 0), oi.unit_price, 'online'
    FROM online_items oi JOIN online_orders o USING (order_number)
    WHERE {CANCELLED} AND oi.item_number IS NOT NULL
"""

# Everything looks "new" at the start of the history, so first-time-purchase
# stats only count from this many days after the earliest receipt.
NEWNESS_WARMUP_DAYS = 90

# An item counts as a regular after this many separate purchase days, and as
# dropped once it's gone this many typical intervals without a purchase.
RESTOCK_MIN_DAYS = 3
RESTOCK_LAPSED_FACTOR = 3

TRIP_BUCKETS = [0, 50, 100, 150, 200, 300, 500]


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
        f"SELECT l.item_number, COALESCE(MAX(p.name), MAX(l.description)) AS name FROM ("
        "SELECT item_number, description FROM receipt_items UNION ALL "
        "SELECT item_number, description FROM online_items) l "
        f"LEFT JOIN products p ON p.item_number = l.item_number WHERE l.item_number IN ({marks}) "
        "GROUP BY l.item_number",
        item_numbers,
    ).fetchall()
    return {r["item_number"]: r["name"] for r in rows}


def insights(conn: sqlite3.Connection, start: str | None, end: str | None, limit: int = 10) -> dict:
    lo, hi = _range(start, end)
    stats = _item_stats(conn, lo, hi)
    bought = [s for s in stats if s["units"] > 0]
    changes = price_changes(conn, lo, hi)
    firsts = _first_bought(conn)
    counted_from = max(lo, _newness_start(conn) or hi)
    new = [
        {**s, "first_bought": firsts[s["item_number"]]}
        for s in bought if counted_from <= firsts.get(s["item_number"], "") <= hi
    ]
    return {
        "new_items": sorted(new, key=lambda s: s["first_bought"], reverse=True)[:limit],
        "top_spent": sorted(bought, key=lambda s: s["spend"], reverse=True)[:limit],
        "most_purchased": sorted(bought, key=lambda s: (s["purchases"], s["units"]), reverse=True)[:limit],
        "most_expensive": sorted(
            (s for s in bought if s["avg_unit_price"]), key=lambda s: s["avg_unit_price"], reverse=True
        )[:limit],
        "price_increases": [c for c in changes if c["change"] > 0][:limit],
        "price_decreases": sorted((c for c in changes if c["change"] < 0), key=lambda c: c["change_pct"])[:limit],
    }


def _first_bought(conn: sqlite3.Connection) -> dict[str, str]:
    return {r[0]: r[1] for r in conn.execute(f"SELECT item_number, MIN(d) FROM ({BOUGHT_LINES}) GROUP BY item_number")}


def _newness_start(conn: sqlite3.Connection) -> str | None:
    """First date where "never bought before" means something (see NEWNESS_WARMUP_DAYS)."""
    earliest = conn.execute(f"SELECT MIN(d) FROM ({BOUGHT_LINES})").fetchone()[0]
    if not earliest:
        return None
    return (date.fromisoformat(earliest) + timedelta(days=NEWNESS_WARMUP_DAYS)).isoformat()


def basket(conn: sqlite3.Connection, start: str | None, end: str | None) -> dict:
    """What a warehouse trip looks like: size distribution, typical trip, biggest trip."""
    lo, hi = _range(start, end)
    trips = [
        dict(r) for r in conn.execute(
            "SELECT r.barcode, r.txn_date AS date, r.warehouse_name AS warehouse, r.total, "
            "COALESCE((SELECT SUM(ri.units) FROM receipt_items ri WHERE ri.barcode = r.barcode "
            "AND ri.is_gas = 0 AND ri.units > 0), 0) AS items "
            "FROM receipts r WHERE r.channel = 'warehouse' AND r.total > 0 AND r.txn_date BETWEEN ? AND ? "
            "ORDER BY r.txn_date",
            (lo, hi),
        )
    ]
    if not trips:
        return {"trips": 0, "buckets": []}

    edges = TRIP_BUCKETS + [float("inf")]
    buckets = []
    for a, b in zip(edges, edges[1:]):
        inside = [t["total"] for t in trips if a <= t["total"] < b]
        label = f"${a}+" if b == float("inf") else (f"Under ${b}" if a == 0 else f"${a}–{b}")
        buckets.append({"label": label, "trips": len(inside), "spend": round(sum(inside), 2)})

    days = sorted({date.fromisoformat(t["date"]) for t in trips})
    gaps = [(b - a).days for a, b in zip(days, days[1:])]
    biggest = max(trips, key=lambda t: t["total"])
    totals = [t["total"] for t in trips]
    items = [t["items"] for t in trips]
    return {
        "trips": len(trips),
        "avg_total": round(statistics.mean(totals), 2),
        "median_total": round(statistics.median(totals), 2),
        "avg_items": round(statistics.mean(items), 1),
        "median_items": statistics.median(items),
        "median_days_between": statistics.median(gaps) if gaps else None,
        "biggest": {**biggest, "total": round(biggest["total"], 2)},
        "buckets": buckets,
    }


def newness(conn: sqlite3.Connection, start: str | None, end: str | None) -> dict:
    """How much spending went to things you'd never bought before, by month."""
    lo, hi = _range(start, end)
    warm = _newness_start(conn)
    if warm is None or max(lo, warm) > hi:
        return {"counted_from": None, "monthly": []}
    counted_from = max(lo, warm)
    rows = conn.execute(
        f"""
        WITH lines AS ({BOUGHT_LINES}),
        firsts AS (SELECT item_number, MIN(d) AS first FROM lines GROUP BY item_number)
        SELECT substr(l.d, 1, 7) AS month,
               SUM(CASE WHEN l.d = f.first THEN l.spend ELSE 0 END) AS new,
               SUM(CASE WHEN l.d > f.first THEN l.spend ELSE 0 END) AS repeat,
               COUNT(DISTINCT CASE WHEN l.d = f.first THEN l.item_number END) AS new_items
        FROM lines l JOIN firsts f USING (item_number)
        WHERE l.d BETWEEN ? AND ?
        GROUP BY month ORDER BY month
        """,
        (counted_from, hi),
    ).fetchall()
    monthly = [
        {"month": r["month"], "new": round(r["new"], 2), "repeat": round(r["repeat"], 2), "new_items": r["new_items"]}
        for r in rows
    ]
    new = sum(m["new"] for m in monthly)
    repeat = sum(m["repeat"] for m in monthly)
    return {
        "counted_from": counted_from,
        "new_spend": round(new, 2),
        "repeat_spend": round(repeat, 2),
        "new_share": round(100 * new / (new + repeat), 1) if new + repeat > 0 else None,
        "new_items": sum(m["new_items"] for m in monthly),
        "monthly": monthly,
    }


def inflation(conn: sqlite3.Connection, start: str | None, end: str | None, today: date) -> dict:
    """Your own price inflation, from warehouse shelf prices (before instant savings).

    Headline: this period's basket (items also bought in the same-length period
    just before) at this period's prices vs. the earlier prices. Monthly: what
    you paid vs. each item's usual (all-history average) price, for items
    bought on more than one day.
    """
    hi = date.fromisoformat(end) if end else today
    lo = date.fromisoformat(start) if start else hi - timedelta(days=364)
    length = (hi - lo).days + 1
    prior_lo, prior_hi = lo - timedelta(days=length), lo - timedelta(days=1)

    rows = conn.execute(
        f"SELECT item_number, d, units, unit_price FROM ({BOUGHT_LINES}) "
        "WHERE src = 'warehouse' AND unit_price > 0"
    ).fetchall()

    def avg_prices(a: date, b: date) -> dict[str, tuple[float, float]]:
        """item -> (units, units-weighted shelf price) within [a, b]."""
        units, cost = defaultdict(float), defaultdict(float)
        for r in rows:
            if a.isoformat() <= r["d"] <= b.isoformat():
                units[r["item_number"]] += r["units"]
                cost[r["item_number"]] += r["units"] * r["unit_price"]
        return {i: (units[i], cost[i] / units[i]) for i in units}

    now, before = avg_prices(lo, hi), avg_prices(prior_lo, prior_hi)
    shared = [i for i in now if i in before]
    paid = sum(now[i][0] * now[i][1] for i in shared)
    at_prior = sum(now[i][0] * before[i][1] for i in shared)
    names = _names(conn, shared)
    drivers = sorted(
        (
            {
                "item_number": i,
                "name": names.get(i),
                "prior_price": round(before[i][1], 2),
                "price": round(now[i][1], 2),
                "impact": round(now[i][0] * (now[i][1] - before[i][1]), 2),
            }
            for i in shared
        ),
        key=lambda d: abs(d["impact"]),
        reverse=True,
    )

    usual = avg_prices(date.min, date.max)
    days_bought = defaultdict(set)
    for r in rows:
        days_bought[r["item_number"]].add(r["d"])
    month_paid, month_usual = defaultdict(float), defaultdict(float)
    m_lo, m_hi = start or "0000-01-01", end or "9999-12-31"
    for r in rows:
        if len(days_bought[r["item_number"]]) > 1 and m_lo <= r["d"] <= m_hi:
            m = r["d"][:7]
            month_paid[m] += r["units"] * r["unit_price"]
            month_usual[m] += r["units"] * usual[r["item_number"]][1]

    return {
        "period_start": lo.isoformat(),
        "period_end": hi.isoformat(),
        "prior_start": prior_lo.isoformat(),
        "prior_end": prior_hi.isoformat(),
        "items_compared": len(shared),
        "paid": round(paid, 2),
        "at_prior_prices": round(at_prior, 2),
        "change_pct": round(100 * (paid / at_prior - 1), 1) if at_prior else None,
        "drivers": [d for d in drivers if d["impact"] != 0][:10],
        "monthly": [
            {"month": m, "vs_usual_pct": round(100 * (month_paid[m] / month_usual[m] - 1), 1)}
            for m in sorted(month_paid) if month_usual[m]
        ],
    }


def restock(conn: sqlite3.Connection, today: date) -> list[dict]:
    """Things you buy regularly, how often, and when you're next due to buy them."""
    days: dict[str, dict[str, dict]] = defaultdict(dict)
    for r in conn.execute(f"SELECT item_number, d, units, unit_price FROM ({BOUGHT_LINES}) ORDER BY d"):
        day = days[r["item_number"]].setdefault(r["d"], {"units": 0.0, "price": None})
        day["units"] += r["units"]
        day["price"] = r["unit_price"] or day["price"]

    out = []
    for item, by_day in days.items():
        if len(by_day) < RESTOCK_MIN_DAYS:
            continue
        dates = [date.fromisoformat(d) for d in by_day]
        every = statistics.median((b - a).days for a, b in zip(dates, dates[1:]))
        since = (today - dates[-1]).days
        if every <= 0 or since > RESTOCK_LAPSED_FACTOR * every:
            continue
        due = dates[-1] + timedelta(days=round(every))
        out.append({
            "item_number": item,
            "days_bought": len(dates),
            "every_days": round(every),
            "last_bought": dates[-1].isoformat(),
            "days_since": since,
            "due_on": due.isoformat(),
            "due_in": (due - today).days,
            "usual_units": statistics.median(d["units"] for d in by_day.values()),
            "last_price": next((d["price"] for d in reversed(by_day.values()) if d["price"]), None),
        })
    names = _names(conn, [o["item_number"] for o in out])
    for o in out:
        o["name"] = names.get(o["item_number"])
    out.sort(key=lambda o: (o["due_in"], o["every_days"]))
    return out


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
