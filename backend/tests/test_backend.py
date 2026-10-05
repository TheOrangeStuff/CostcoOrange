from datetime import date, timedelta

from fastapi.testclient import TestClient

from app import analytics, auth, db, sync
from app.normalize import normalize_receipt


def item(number, desc, units, amount, unit_price=None, dept=17):
    return {"itemNumber": number, "itemDescription01": desc, "unit": units, "amount": amount,
            "itemUnitPriceAmount": unit_price if unit_price is not None else (amount / units if units else 0),
            "itemDepartmentNumber": dept}


def receipt(barcode, day, items, total=None, warehouse="HUNTSVILLE", doc="WarehouseReceiptDetail"):
    return {"transactionBarcode": barcode, "transactionDate": day, "transactionDateTime": f"{day}T12:00:00",
            "warehouseName": warehouse, "warehouseNumber": "1234", "documentType": doc,
            "total": total if total is not None else sum(i["amount"] for i in items),
            "itemArray": items}


class FakeClient:
    def __init__(self, receipts=(), orders=(), details=None):
        self.receipts, self.orders, self.details = list(receipts), list(orders), details or {}

    def list_receipts(self, start, end):
        m, d, y = map(int, start.split("/"))
        lo = date(y, m, d).isoformat()
        m, d, y = map(int, end.split("/"))
        hi = date(y, m, d).isoformat()
        return [r for r in self.receipts if lo <= r["transactionDate"] <= hi]

    def list_online_orders(self, start, end):
        lo, hi = (date(*map(int, s.split("-"))).isoformat() for s in (start, end))
        return [o for o in self.orders if lo <= o["orderPlacedDate"][:10] <= hi]

    def order_detail(self, number):
        return self.details.get(number)

    def product_names(self, numbers, warehouse):
        return {"111": "Kirkland Signature Organic Eggs, 24 ct"}


def test_instant_savings_fold_into_item():
    row, items = normalize_receipt(receipt("B1", "2026-09-01", [
        item("111", "KS ORG EGGS", 1, 8.99),
        {"itemNumber": "999888", "itemDescription01": "/111", "unit": -1, "amount": -2.00},
        item("222", "BANANAS", 1, 1.99),
    ]))
    assert row["channel"] == "warehouse"
    assert [i["item_number"] for i in items] == ["111", "222"]
    assert items[0]["discount"] == -2.00


def test_gas_receipt_is_separate_channel():
    row, _ = normalize_receipt(receipt("G1", "2026-09-01", [item("300", "UNLEADED", 12.3, 40.0, 3.25)],
                                       doc="FuelReceipts"))
    assert row["channel"] == "gas"


def test_sync_backfill_then_notify_and_price_adjustment():
    today = date(2026, 9, 30)
    client = FakeClient(receipts=[
        receipt("B1", "2026-09-05", [item("111", "KS ORG EGGS", 2, 17.98, 8.99), item("222", "BANANAS", 1, 1.99)]),
        receipt("G1", "2026-09-06", [item("300", "UNLEADED", 10, 32.5, 3.25)], doc="FuelReceipts"),
    ], orders=[
        {"orderNumber": "O1", "orderPlacedDate": "2026-09-10T00:00:00", "orderTotal": 54.10, "status": "Delivered"},
    ], details={"O1": {"orderPlacedDate": "2026-09-10", "orderTotal": 54.10, "merchandiseTotal": 49.99,
                       "status": "Delivered", "shipToAddress": [{"orderLineItems": [
                           {"itemNumber": "555", "itemDescription": "Towels", "price": 49.99, "quantity": 1,
                            "merchandiseTotalAmount": 49.99}]}]}})

    first = sync.sync(client, today)
    assert first == {"ok": True, "new_receipts": 2, "new_orders": 1, "error": None}
    with db.session() as conn:
        assert conn.execute("SELECT COUNT(*) FROM events").fetchone()[0] == 0  # backfill is silent

    # Eggs ring up cheaper (instant savings) two weeks later.
    client.receipts.append(receipt("B2", "2026-09-20", [
        item("111", "KS ORG EGGS", 1, 8.99),
        {"itemNumber": "777", "itemDescription01": "/111", "unit": -1, "amount": -3.00},
    ]))
    second = sync.sync(client, today)
    assert second["new_receipts"] == 1

    with db.session() as conn:
        kinds = [r["kind"] for r in conn.execute("SELECT kind FROM events ORDER BY id")]
        assert kinds == ["new_receipt", "price_drop"]
        adj = analytics.price_adjustments(conn, today)
        assert len(adj) == 1
        assert adj[0]["item_number"] == "111"
        assert adj[0]["refund_estimate"] == 6.00
        assert adj[0]["claim_by"] == "2026-10-05"
        assert adj[0]["name"] == "Kirkland Signature Organic Eggs, 24 ct"

        s = analytics.summary(conn, None, None)
        assert s["warehouse_spend"] == round(19.97 + 5.99, 2)
        assert s["online_spend"] == 54.10
        assert s["gas_spend"] == 32.5
        assert s["trips"] == 2

        ex = analytics.executive(conn, today)
        assert ex["eligible_spend"] == round(19.97 + 5.99 + 49.99, 2)

        ins = analytics.insights(conn, None, None)
        assert ins["top_spent"][0]["item_number"] == "555"
        assert ins["price_increases"] == []  # shelf price unchanged; only savings

        rows = list(analytics.export_rows(conn, None, None))
        assert len(rows) == 1 + 4 + 1  # header, 4 warehouse+gas lines, 1 online line


def test_api_requires_login_and_serves_summary():
    from app.main import app
    auth.create_user("mom", "correct horse")
    with TestClient(app) as c:
        assert c.get("/api/summary").status_code == 401
        assert c.post("/api/auth/login", json={"username": "mom", "password": "nope"}).status_code == 401
        # Phone keyboards capitalize the first letter.
        assert c.post("/api/auth/login", json={"username": "Mom", "password": "correct horse"}).status_code == 200
        token = c.post("/api/auth/login", json={"username": "mom", "password": "correct horse"}).json()["token"]
        h = {"Authorization": f"Bearer {token}"}
        body = c.get("/api/summary", headers=h).json()
        assert body["summary"]["total_spend"] == 0
        assert c.get("/api/status", headers=h).json()["costco_linked"] is False
        assert c.get("/api/export.csv", headers=h).text.startswith("date,channel")


def test_gateway_error_is_unwrapped():
    from app.costco_client import _error_detail
    import json
    inner = json.dumps({"errorType": "ClientError", "errorMessage": "Error trying to resolve field 'receiptsWithCounts'"})
    body = json.dumps({"context": {"statusMessage": {"statusCode": "APIGATEWAY.BACKEND.FAULT", "errorCode": "400",
                                                     "description": {"value": inner}}}})
    assert _error_detail(body) == "Error trying to resolve field 'receiptsWithCounts'"
    assert _error_detail("<html>nope</html>") == "<html>nope</html>"


def test_logs_collect_app_reports_and_sync_failures():
    from app.main import app

    class Broken(FakeClient):
        def list_receipts(self, start, end):
            raise RuntimeError("Costco said no")

    auth.create_user("dad", "pw")
    with TestClient(app) as c:
        token = c.post("/api/auth/login", json={"username": "dad", "password": "pw"}).json()["token"]
        h = {"Authorization": f"Bearer {token}"}
        assert c.post("/api/logs", json={"message": "boom"}).status_code == 401
        assert c.post("/api/logs", headers=h, json={"message": "POST /api/sync failed", "device": "Pixel"}).json()["ok"]
        assert sync.sync(Broken(), date(2026, 9, 30))["error"] == "Costco said no"
        logs = c.get("/api/logs", headers=h).json()
        assert "Costco said no" in logs[0]["message"] and logs[0]["source"] == "server"
        app_logs = c.get("/api/logs?source=app", headers=h).json()
        assert [(l["message"], l["username"], l["device"]) for l in app_logs] == [("POST /api/sync failed", "dad", "Pixel")]


def test_habits_restock_inflation_basket_newness():
    today = date(2026, 9, 30)
    receipts = []
    # Milk every 10 days for a year; its price goes from 4.00 to 4.40 in 2026.
    d = date(2025, 6, 1)
    while d <= date(2026, 9, 20):
        price = 4.40 if d.year == 2026 else 4.00
        receipts.append(receipt(f"M{d}", d.isoformat(), [item("100", "MILK", 2, 2 * price, price)]))
        d += timedelta(days=10)
    # Trips that also pick up something new, well past the warm-up period.
    receipts.append(receipt("T1", "2026-08-01", [item("200", "TV", 1, 499.99), item("100", "MILK", 1, 4.40)]))
    receipts.append(receipt("T2", "2026-09-01", [item("300", "COUCH", 1, 899.99)]))
    with db.session() as conn:
        for r in receipts:
            sync.store_receipt(conn, r)

    with db.session() as conn:
        due = analytics.restock(conn, today)
        assert [r["item_number"] for r in due] == ["100"]
        assert due[0]["every_days"] == 10 and due[0]["last_bought"] == "2026-09-14" and due[0]["due_in"] == -6

        inf = analytics.inflation(conn, "2026-01-01", "2026-06-30", today)
        assert inf["items_compared"] == 1 and inf["change_pct"] == 10.0
        assert inf["drivers"][0]["item_number"] == "100" and inf["drivers"][0]["impact"] > 0
        months = {m["month"]: m["vs_usual_pct"] for m in analytics.inflation(conn, None, None, today)["monthly"]}
        assert months["2025-07"] < 0 < months["2026-07"]

        b = analytics.basket(conn, "2026-08-01", "2026-09-30")
        assert b["biggest"]["barcode"] == "T2" and b["buckets"][-1]["trips"] == 2

        n = analytics.newness(conn, None, None)
        assert n["counted_from"] == "2025-08-30"
        assert n["new_spend"] == round(499.99 + 899.99, 2) and n["new_items"] == 2
        ins = analytics.insights(conn, None, None)
        assert [i["item_number"] for i in ins["new_items"]] == ["300", "200"]
