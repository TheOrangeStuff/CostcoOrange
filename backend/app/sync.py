"""Pull receipts and online orders from Costco into the local database."""

import json
import logging
import sqlite3
import threading
from datetime import date, datetime, timedelta, timezone

from app import analytics, config, db
from app.costco_client import CostcoAuthError, CostcoClient
from app.normalize import is_cancelled, normalize_order, normalize_receipt

logger = logging.getLogger(__name__)

_lock = threading.Lock()

# Keep product-name lookups gentle; the rest get picked up on later syncs.
PRODUCT_LOOKUPS_PER_RUN = 200


def _receipt_fmt(d: date) -> str:
    return f"{d.month}/{d.day}/{d.year}"


def _order_fmt(d: date) -> str:
    return f"{d.year}-{d.month}-{d.day}"


def _windows(start: date, end: date, days: int = 90):
    cur = start
    while cur <= end:
        stop = min(cur + timedelta(days=days - 1), end)
        yield cur, stop
        cur = stop + timedelta(days=1)


def store_receipt(conn: sqlite3.Connection, raw: dict) -> dict | None:
    """Insert a receipt if unseen. Returns the receipt row when it was new."""
    db.archive_payload(conn, "receipt", raw.get("transactionBarcode"), raw)
    row, items = normalize_receipt(raw)
    if not row["barcode"] or not row["txn_date"]:
        logger.warning("Kept a receipt Costco sent that couldn't be parsed (see payloads table)")
        return None
    exists = conn.execute("SELECT 1 FROM receipts WHERE barcode = ?", (row["barcode"],)).fetchone()
    if exists:
        return None
    conn.execute(
        "INSERT INTO receipts (barcode, txn_date, txn_datetime, channel, warehouse_number, warehouse_name, "
        "total, subtotal, taxes, raw, first_seen) VALUES (:barcode, :txn_date, :txn_datetime, :channel, "
        ":warehouse_number, :warehouse_name, :total, :subtotal, :taxes, :raw, :first_seen)",
        {**row, "raw": json.dumps(raw), "first_seen": db.now_iso()},
    )
    _insert_receipt_items(conn, row["barcode"], items)
    row["item_count"] = sum(1 for i in items if i["units"] > 0)
    return row


def _insert_receipt_items(conn: sqlite3.Connection, barcode: str, items: list[dict]) -> None:
    conn.executemany(
        "INSERT INTO receipt_items (barcode, line, item_number, description, department, units, amount, "
        "unit_price, discount, is_gas) VALUES (:barcode, :line, :item_number, :description, :department, "
        ":units, :amount, :unit_price, :discount, :is_gas)",
        [{**i, "barcode": barcode} for i in items],
    )


def store_order(conn: sqlite3.Connection, raw: dict, detail: dict | None) -> tuple[dict, bool]:
    """Insert or refresh an online order. Returns (row, was_new).

    A refresh never blanks out what's stored: missing dates/totals and an
    empty item list from Costco keep the previous values."""
    row, items = normalize_order(raw, detail)
    db.archive_payload(conn, "order", row["order_number"], raw)
    if detail:
        db.archive_payload(conn, "order_detail", row["order_number"], detail)
    existing = conn.execute(
        "SELECT order_number FROM online_orders WHERE order_number = ?", (row["order_number"],)
    ).fetchone()
    params = {**row, "raw": json.dumps(raw), "detail": json.dumps(detail) if detail else None}
    if existing:
        conn.execute(
            "UPDATE online_orders SET order_date = COALESCE(NULLIF(:order_date, ''), order_date), "
            "total = COALESCE(NULLIF(:total, 0), total), "
            "merchandise_total = COALESCE(:merchandise_total, merchandise_total), "
            "status = COALESCE(:status, status), "
            "raw = :raw, detail = COALESCE(:detail, detail) WHERE order_number = :order_number",
            params,
        )
        if detail and items:
            conn.execute("DELETE FROM online_items WHERE order_number = ?", (row["order_number"],))
    else:
        conn.execute(
            "INSERT INTO online_orders (order_number, order_date, total, merchandise_total, status, raw, detail, "
            "first_seen) VALUES (:order_number, :order_date, :total, :merchandise_total, :status, :raw, :detail, "
            ":first_seen)",
            {**params, "first_seen": db.now_iso()},
        )
    if (detail and items) or not existing:
        conn.executemany(
            "INSERT INTO online_items (order_number, line, item_number, description, unit_price, quantity, amount) "
            "VALUES (:order_number, :line, :item_number, :description, :unit_price, :quantity, :amount)",
            [{**i, "order_number": row["order_number"]} for i in items],
        )
    return row, not existing


# Bump when normalize.py changes how stored rows are derived; the server
# re-parses everything once on startup. 2: gallons on fuel lines, car washes aren't fuel.
NORMALIZE_VERSION = 2


def renormalize_if_stale() -> None:
    with db.session() as conn:
        current = int(db.get_state(conn, "normalize_version") or 1)
    if current >= NORMALIZE_VERSION:
        return
    logger.info("Re-parsing stored receipts (normalize version %s -> %s)", current, NORMALIZE_VERSION)
    db.snapshot(f"before-renormalize-v{NORMALIZE_VERSION}")
    renormalize_all()


def renormalize_all() -> None:
    """Rebuild item rows from stored raw payloads (after a parsing fix)."""
    with db.session() as conn:
        db.set_state(conn, "normalize_version", str(NORMALIZE_VERSION))
        for r in conn.execute("SELECT barcode, raw FROM receipts").fetchall():
            row, items = normalize_receipt(json.loads(r["raw"]))
            conn.execute("DELETE FROM receipt_items WHERE barcode = ?", (r["barcode"],))
            conn.execute(
                "UPDATE receipts SET txn_date = ?, channel = ?, total = ? WHERE barcode = ?",
                (row["txn_date"], row["channel"], row["total"], r["barcode"]),
            )
            _insert_receipt_items(conn, r["barcode"], items)
        for o in conn.execute("SELECT raw, detail FROM online_orders").fetchall():
            store_order(conn, json.loads(o["raw"]), json.loads(o["detail"]) if o["detail"] else None)


def _money(v: float) -> str:
    return f"${v:,.2f}"


def _pretty_date(iso: str) -> str:
    return datetime.strptime(iso, "%Y-%m-%d").strftime("%b %-d")


def sync(client: CostcoClient | None = None, today: date | None = None) -> dict:
    if not _lock.acquire(blocking=False):
        return {"ok": False, "error": "A sync is already running"}
    try:
        return _sync(client or CostcoClient(), today or date.today())
    finally:
        _lock.release()


def _sync(client: CostcoClient, today: date) -> dict:
    with db.session() as conn:
        run_id = conn.execute(
            "INSERT INTO sync_runs (started_at) VALUES (?)", (db.now_iso(),)
        ).lastrowid
        backfilled = db.get_state(conn, "backfilled") == "1"

    # The first sync pulls everything Costco still has, silently; later
    # syncs re-scan a recent window and announce anything new.
    start = today - timedelta(days=config.RESCAN_DAYS if backfilled else config.BACKFILL_DAYS)
    notify = backfilled
    new_receipts = new_orders = 0
    error = None

    try:
        for lo, hi in _windows(start, today):
            receipts = client.list_receipts(_receipt_fmt(lo), _receipt_fmt(hi))
            with db.session() as conn:
                for raw in receipts:
                    row = store_receipt(conn, raw)
                    if not row:
                        continue
                    new_receipts += 1
                    if notify and row["channel"] == "warehouse":
                        kind = "Return" if row["total"] < 0 else "New receipt"
                        db.add_event(
                            conn, "new_receipt", row["barcode"],
                            f"{kind}: {_money(row['total'])}",
                            f"{row['warehouse_name'] or 'Costco'} · {row['item_count']} items · "
                            f"{_pretty_date(row['txn_date'])}",
                        )

        for lo, hi in _windows(start, today):
            orders = client.list_online_orders(_order_fmt(lo), _order_fmt(hi))
            for raw in orders:
                number = str(raw.get("orderNumber") or raw.get("orderHeaderId"))
                with db.session() as conn:
                    prev = conn.execute(
                        "SELECT status, detail IS NOT NULL AS has_detail FROM online_orders WHERE order_number = ?",
                        (number,),
                    ).fetchone()
                needs_detail = not prev or not prev["has_detail"] or prev["status"] != raw.get("status")
                detail = client.order_detail(number) if needs_detail else None
                with db.session() as conn:
                    row, was_new = store_order(conn, raw, detail)
                    if was_new:
                        new_orders += 1
                        if notify and not is_cancelled(row["status"]):
                            db.add_event(
                                conn, "new_order", row["order_number"],
                                f"New online order: {_money(row['total'])}",
                                f"Costco.com · {_pretty_date(row['order_date'])}",
                            )

        _lookup_product_names(client)

        with db.session() as conn:
            db.set_state(conn, "backfilled", "1")
            analytics.record_price_drop_events(conn, today)
    except CostcoAuthError as e:
        logger.warning("Costco auth failed: %s", e)
        error = "Costco sign-in expired or was rejected"
        with db.session() as conn:
            db.add_event(
                conn, "relink_needed", today.isoformat(),
                "Re-link your Costco account",
                "Costco's sign-in expired, so syncing has stopped. Open the app and sign in to Costco again.",
            )
    except Exception as e:  # noqa: BLE001 - log every failure on the run row
        logger.exception("Sync failed")
        error = str(e)

    with db.session() as conn:
        _check_link_age(conn, today)
        conn.execute(
            "UPDATE sync_runs SET finished_at = ?, ok = ?, new_receipts = ?, new_orders = ?, error = ? WHERE id = ?",
            (db.now_iso(), 0 if error else 1, new_receipts, new_orders, error, run_id),
        )
    return {"ok": error is None, "new_receipts": new_receipts, "new_orders": new_orders, "error": error}


def _lookup_product_names(client: CostcoClient) -> None:
    with db.session() as conn:
        rows = conn.execute(
            "SELECT ri.item_number, MAX(r.warehouse_number) AS warehouse "
            "FROM receipt_items ri JOIN receipts r USING (barcode) "
            "LEFT JOIN products p ON p.item_number = ri.item_number "
            "WHERE p.item_number IS NULL AND ri.is_gas = 0 AND ri.units > 0 "
            "GROUP BY ri.item_number LIMIT ?",
            (PRODUCT_LOOKUPS_PER_RUN,),
        ).fetchall()
    by_warehouse: dict[str, list[str]] = {}
    for r in rows:
        by_warehouse.setdefault(r["warehouse"] or "847", []).append(r["item_number"])
    for warehouse, numbers in by_warehouse.items():
        try:
            names = client.product_names(numbers, warehouse)
        except Exception:  # noqa: BLE001 - names are a nicety; never fail the sync over them
            logger.exception("Product lookup failed")
            return
        with db.session() as conn:
            # Store misses too (name NULL) so they aren't retried every run.
            conn.executemany(
                "INSERT OR REPLACE INTO products (item_number, name, looked_up_at) VALUES (?, ?, ?)",
                [(n, names.get(n), db.now_iso()) for n in numbers],
            )


def _check_link_age(conn: sqlite3.Connection, today: date) -> None:
    row = conn.execute("SELECT linked_at FROM costco_auth WHERE id = 1").fetchone()
    if not row or not row["linked_at"]:
        return
    linked = datetime.fromisoformat(row["linked_at"]).astimezone(timezone.utc).date()
    age = (today - linked).days
    if age >= config.RELINK_WARN_DAYS:
        db.add_event(
            conn, "relink_soon", row["linked_at"],
            "Costco sign-in expires soon",
            f"It's been {age} days since you linked Costco. Sign in again in the app to keep syncing.",
        )
