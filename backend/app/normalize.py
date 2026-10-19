"""Turn raw Costco payloads into rows. Pure functions, so tests can feed fixtures."""

import re

GAS_KEYWORDS = ("gasoline", "gas ", "gas-", "gas/", "unleaded", "premium", "diesel", "fuel")


def _num(value, default: float = 0.0) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def is_gas_item(item: dict) -> bool:
    desc = f"{item.get('itemDescription01') or ''} {item.get('itemDescription02') or ''}".lower()
    # Car wash packages can be named after fuel grades ("PREMIUM WASH").
    return "wash" not in desc and any(k in desc for k in GAS_KEYWORDS)


def receipt_date(receipt: dict) -> str:
    raw = receipt.get("transactionDate") or receipt.get("transactionDateTime") or ""
    return raw[:10]


def normalize_receipt(receipt: dict) -> tuple[dict, list[dict]]:
    """Returns (receipt row, item rows).

    Instant savings show up as their own negative line whose description is
    "/<item number>"; they're folded into the discount of the line they apply to.
    """
    items: list[dict] = []
    for raw in receipt.get("itemArray") or []:
        number = str(raw.get("itemNumber") or "").strip()
        desc = (raw.get("itemDescription01") or "").strip()
        amount = _num(raw.get("amount"))
        units = _num(raw.get("unit"))

        if desc.startswith("/"):
            target = re.sub(r"\D", "", desc)
            parent = next((i for i in reversed(items) if i["item_number"] == target), None)
            if parent is not None:
                parent["discount"] += amount
                continue
            # Savings we can't attach still count toward what was paid.
            desc, units = "Instant savings", 0.0

        if not number:
            continue
        gas = is_gas_item(raw)
        if gas:
            # Fuel lines report gallons in fuelUnitQuantity; "unit" isn't reliably gallons.
            units = _num(raw.get("fuelUnitQuantity"), 0.0) or units
            if amount < 0:
                units = -abs(units)
        unit_price = _num(raw.get("itemUnitPriceAmount"), 0.0) or (amount / units if units else None)
        items.append({
            "line": len(items),
            "item_number": number,
            "description": desc,
            "department": int(raw["itemDepartmentNumber"]) if raw.get("itemDepartmentNumber") else None,
            "units": units,
            "amount": amount,
            "unit_price": abs(unit_price) if unit_price else None,
            "discount": 0.0,
            "is_gas": 1 if gas else 0,
        })

    doc_type = (receipt.get("documentType") or "").lower()
    all_gas = bool(items) and all(i["is_gas"] for i in items)
    channel = "gas" if all_gas or "fuel" in doc_type or "gas" in doc_type or "carwash" in doc_type else "warehouse"

    row = {
        "barcode": str(receipt.get("transactionBarcode") or receipt.get("transactionNumber")),
        "txn_date": receipt_date(receipt),
        "txn_datetime": receipt.get("transactionDateTime"),
        "channel": channel,
        "warehouse_number": str(receipt.get("warehouseNumber") or "") or None,
        "warehouse_name": receipt.get("warehouseName") or receipt.get("warehouseShortName"),
        "total": _num(receipt.get("total")),
        "subtotal": _num(receipt.get("subTotal"), None),
        "taxes": _num(receipt.get("taxes"), None),
    }
    return row, items


def normalize_order(order: dict, detail: dict | None) -> tuple[dict, list[dict]]:
    source = detail or order
    row = {
        "order_number": str(order.get("orderNumber") or order.get("orderHeaderId")),
        "order_date": (source.get("orderPlacedDate") or order.get("orderPlacedDate") or "")[:10],
        "total": _num(source.get("orderTotal")),
        "merchandise_total": _num(detail.get("merchandiseTotal"), None) if detail else None,
        "status": source.get("status") or order.get("status"),
    }

    items: list[dict] = []
    if detail:
        for ship_to in detail.get("shipToAddress") or []:
            for li in ship_to.get("orderLineItems") or []:
                qty = _num(li.get("quantity"), 1.0)
                price = _num(li.get("price"), None)
                amount = _num(li.get("merchandiseTotalAmount"), None)
                if amount is None and price is not None:
                    amount = price * qty
                items.append({
                    "line": len(items),
                    "item_number": str(li.get("itemNumber") or "") or None,
                    "description": li.get("itemDescription"),
                    "unit_price": price,
                    "quantity": qty,
                    "amount": amount,
                })
    else:
        for li in order.get("orderLineItems") or []:
            items.append({
                "line": len(items),
                "item_number": str(li.get("itemNumber") or "") or None,
                "description": li.get("itemDescription"),
                "unit_price": None,
                "quantity": None,
                "amount": None,
            })
    return row, items


def is_cancelled(status: str | None) -> bool:
    return bool(status) and "cancel" in status.lower()
