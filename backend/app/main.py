"""HTTP API for the Android app. Run with: uv run uvicorn app.main:app --port 7057"""

import asyncio
import csv
import io
import logging
from contextlib import asynccontextmanager
from datetime import date, datetime, timezone

from fastapi import BackgroundTasks, Depends, FastAPI, HTTPException, Query
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from app import analytics, auth, config, costco_client, db, logs, sync

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


async def _scheduler() -> None:
    await asyncio.sleep(30)
    while True:
        with db.session() as conn:
            linked = conn.execute(
                "SELECT 1 FROM costco_auth WHERE id = 1 AND refresh_token IS NOT NULL"
            ).fetchone()
        if linked:
            result = await asyncio.to_thread(sync.sync)
            logger.info("Scheduled sync: %s", result)
        await asyncio.sleep(config.SYNC_INTERVAL_MINUTES * 60)


@asynccontextmanager
async def lifespan(_: FastAPI):
    db.init()
    logs.install()
    task = asyncio.create_task(_scheduler())
    yield
    task.cancel()


app = FastAPI(title="Costco Tracker", lifespan=lifespan)

User = Depends(auth.current_user)


class LoginBody(BaseModel):
    username: str
    password: str
    device: str | None = None


class LinkBody(BaseModel):
    refresh_token: str
    client_id: str | None = None


class LabelBody(BaseModel):
    label: str


class LogBody(BaseModel):
    level: str = Field("error", max_length=16)
    message: str = Field(max_length=20000)
    device: str | None = Field(None, max_length=200)


@app.get("/api/health")
def health():
    return {"ok": True}


@app.post("/api/auth/login")
def login(body: LoginBody):
    return {"token": auth.login(body.username.strip(), body.password, body.device)}


@app.post("/api/auth/logout")
def logout(user=User):
    auth.logout(user["token"])
    return {"ok": True}


@app.get("/api/status")
def status(user=User):
    with db.session() as conn:
        link = conn.execute("SELECT email, linked_at, refreshed_at, last_error FROM costco_auth WHERE id = 1").fetchone()
        last = conn.execute("SELECT * FROM sync_runs ORDER BY id DESC LIMIT 1").fetchone()
        last_ok = conn.execute("SELECT finished_at FROM sync_runs WHERE ok = 1 ORDER BY id DESC LIMIT 1").fetchone()
        counts = conn.execute(
            "SELECT (SELECT COUNT(*) FROM receipts) AS receipts, (SELECT COUNT(*) FROM online_orders) AS orders"
        ).fetchone()
    days = None
    if link and link["linked_at"]:
        days = (datetime.now(timezone.utc) - datetime.fromisoformat(link["linked_at"])).days
    return {
        "user": user["username"],
        "costco_linked": bool(link and link["linked_at"]),
        "costco_email": link["email"] if link else None,
        "linked_at": link["linked_at"] if link else None,
        "days_since_link": days,
        "auth_error": link["last_error"] if link else None,
        "last_sync": dict(last) if last else None,
        "last_successful_sync": last_ok["finished_at"] if last_ok else None,
        "sync_interval_minutes": config.SYNC_INTERVAL_MINUTES,
        "receipts": counts["receipts"],
        "orders": counts["orders"],
    }


@app.post("/api/costco/link")
def link_costco(body: LinkBody, background: BackgroundTasks, user=User):
    try:
        email = costco_client.link_account(body.refresh_token.strip(), body.client_id)
    except costco_client.CostcoAuthError as e:
        raise HTTPException(400, f"Costco rejected that sign-in: {e}")
    background.add_task(sync.sync)
    return {"ok": True, "email": email}


@app.post("/api/sync")
def trigger_sync(background: BackgroundTasks, user=User):
    background.add_task(sync.sync)
    return {"ok": True}


@app.get("/api/summary")
def get_summary(start: str | None = None, end: str | None = None, user=User):
    with db.session() as conn:
        return {
            "summary": analytics.summary(conn, start, end),
            "monthly": analytics.monthly(conn, start, end),
            "categories": analytics.categories(conn, start, end),
            "basket": analytics.basket(conn, start, end),
            "newness": analytics.newness(conn, start, end),
            "inflation": analytics.inflation(conn, start, end, date.today()),
        }


@app.get("/api/insights")
def get_insights(start: str | None = None, end: str | None = None, limit: int = Query(10, le=100), user=User):
    with db.session() as conn:
        return analytics.insights(conn, start, end, limit)


@app.get("/api/restock")
def get_restock(user=User):
    with db.session() as conn:
        return analytics.restock(conn, date.today())


@app.get("/api/price-adjustments")
def get_price_adjustments(user=User):
    with db.session() as conn:
        return analytics.price_adjustments(conn, date.today())


@app.get("/api/membership")
def get_membership(user=User):
    with db.session() as conn:
        return analytics.executive(conn, date.today())


@app.get("/api/receipts")
def list_receipts(start: str | None = None, end: str | None = None, user=User):
    lo, hi = start or "0000-01-01", end or "9999-12-31"
    with db.session() as conn:
        receipts = [
            {**dict(r), "kind": "warehouse" if r["channel"] == "warehouse" else "gas"}
            for r in conn.execute(
                "SELECT r.barcode AS id, r.txn_date AS date, r.channel, r.warehouse_name AS location, r.total, "
                "(SELECT COUNT(*) FROM receipt_items ri WHERE ri.barcode = r.barcode AND ri.units > 0) AS items "
                "FROM receipts r WHERE r.txn_date BETWEEN ? AND ?",
                (lo, hi),
            )
        ]
        orders = [
            {**dict(r), "kind": "online", "channel": "online", "location": "Costco.com"}
            for r in conn.execute(
                "SELECT o.order_number AS id, o.order_date AS date, o.total, o.status, "
                "(SELECT COUNT(*) FROM online_items oi WHERE oi.order_number = o.order_number) AS items "
                "FROM online_orders o WHERE o.order_date BETWEEN ? AND ?",
                (lo, hi),
            )
        ]
    return sorted(receipts + orders, key=lambda r: r["date"], reverse=True)


@app.get("/api/receipts/{barcode}")
def get_receipt(barcode: str, user=User):
    with db.session() as conn:
        r = conn.execute(
            "SELECT barcode, txn_date, txn_datetime, channel, warehouse_name, total, subtotal, taxes "
            "FROM receipts WHERE barcode = ?",
            (barcode,),
        ).fetchone()
        if not r:
            raise HTTPException(404, "No such receipt")
        items = conn.execute(
            "SELECT ri.line, ri.item_number, COALESCE(p.name, ri.description) AS name, ri.description, "
            "ri.department, ri.units, ri.unit_price, ri.amount, ri.discount, ri.amount + ri.discount AS net "
            "FROM receipt_items ri LEFT JOIN products p ON p.item_number = ri.item_number "
            "WHERE ri.barcode = ? ORDER BY ri.line",
            (barcode,),
        ).fetchall()
    return {**dict(r), "items": [dict(i) for i in items]}


@app.get("/api/orders/{order_number}")
def get_order(order_number: str, user=User):
    with db.session() as conn:
        o = conn.execute(
            "SELECT order_number, order_date, total, merchandise_total, status FROM online_orders "
            "WHERE order_number = ?",
            (order_number,),
        ).fetchone()
        if not o:
            raise HTTPException(404, "No such order")
        items = conn.execute(
            "SELECT oi.line, oi.item_number, COALESCE(p.name, oi.description) AS name, oi.unit_price, "
            "oi.quantity, oi.amount FROM online_items oi LEFT JOIN products p ON p.item_number = oi.item_number "
            "WHERE oi.order_number = ? ORDER BY oi.line",
            (order_number,),
        ).fetchall()
    return {**dict(o), "items": [dict(i) for i in items]}


@app.get("/api/items/{item_number}")
def get_item(item_number: str, user=User):
    with db.session() as conn:
        history = analytics.price_history(conn, item_number)
        online = [
            dict(r) for r in conn.execute(
                "SELECT o.order_date AS date, o.order_number, oi.unit_price, oi.quantity, oi.amount "
                "FROM online_items oi JOIN online_orders o USING (order_number) WHERE oi.item_number = ? "
                "ORDER BY o.order_date",
                (item_number,),
            )
        ]
        name = analytics._names(conn, [item_number]).get(item_number)
    if not history and not online:
        raise HTTPException(404, "Never purchased")
    return {"item_number": item_number, "name": name, "warehouse": history, "online": online}


@app.put("/api/departments/{number}")
def label_department(number: int, body: LabelBody, user=User):
    label = body.label.strip()
    with db.session() as conn:
        if label:
            conn.execute(
                "INSERT INTO departments (number, label) VALUES (?, ?) "
                "ON CONFLICT(number) DO UPDATE SET label = excluded.label",
                (number, label),
            )
        else:
            conn.execute("DELETE FROM departments WHERE number = ?", (number,))
    return {"ok": True}


@app.get("/api/events")
def list_events(after: int = 0, user=User):
    """Undismissed notifications newer than `after` (the app remembers the last id it showed)."""
    with db.session() as conn:
        return [
            dict(r) for r in conn.execute(
                "SELECT id, kind, ref, title, body, created_at FROM events "
                "WHERE id > ? AND dismissed = 0 ORDER BY id LIMIT 100",
                (after,),
            )
        ]


@app.post("/api/events/{event_id}/dismiss")
def dismiss_event(event_id: int, user=User):
    with db.session() as conn:
        conn.execute("UPDATE events SET dismissed = 1 WHERE id = ?", (event_id,))
    return {"ok": True}


@app.get("/api/export.csv")
def export_csv(start: str | None = None, end: str | None = None, user=User):
    buf = io.StringIO()
    writer = csv.writer(buf)
    with db.session() as conn:
        for row in analytics.export_rows(conn, start, end):
            writer.writerow(row)
    name = f"costco-{start or 'all'}-to-{end or date.today().isoformat()}.csv"
    return StreamingResponse(
        iter([buf.getvalue()]),
        media_type="text/csv",
        headers={"Content-Disposition": f'attachment; filename="{name}"'},
    )


@app.post("/api/logs")
def report_log(body: LogBody, user=User):
    """Error reports from the app."""
    with db.session() as conn:
        db.add_log(conn, "app", body.level, body.message, user["username"], body.device)
    return {"ok": True}


@app.get("/api/logs")
def list_logs(source: str | None = None, limit: int = Query(100, le=2000), user=User):
    """Recent server warnings/errors and app error reports, newest first."""
    with db.session() as conn:
        return [
            dict(r) for r in conn.execute(
                "SELECT * FROM logs WHERE ? IS NULL OR source = ? ORDER BY id DESC LIMIT ?",
                (source, source, limit),
            )
        ]
