"""Client for Costco's undocumented ecom GraphQL API.

Auth and request shapes come from thehesiod/costco-mcp (refresh-token flow,
Chrome TLS impersonation) and ziningw/costco-receipt-analysis (receipt list
fields, online order details). Everything here can break if Costco changes
their site.
"""

import json
import logging
import time

import jwt
from curl_cffi import requests as curl_requests

from app import config, db

logger = logging.getLogger(__name__)

# Akamai Bot Manager drops connections whose TLS fingerprint isn't a real
# browser's, so every request impersonates Chrome.
IMPERSONATE = "chrome131"
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)

# Azure AD B2C configuration that ships in Costco's own browser bundle.
TENANT_ID = "e0714dd4-784d-46d6-a278-3e29553483eb"
TOKEN_ENDPOINT = f"https://signin.costco.com/{TENANT_ID}/{config.B2C_POLICY}/oauth2/v2.0/token"
MSAL_CLIENT_ID = "a3a5186b-7c89-4b4c-93a8-dd604e930757"
WCS_CLIENT_ID = "4900eb1f-0c10-4bd9-99c3-c59e6c1ecebf"
# Static per-app value from Costco's bundle; a random UUID gets a 401.
CLIENT_IDENTIFIER = "481b1aec-aa3b-454b-b81b-48187e28f205"

ORDERS_ENDPOINT = "https://ecom-api.costco.com/ebusiness/order/v1/orders/graphql"
PRODUCTS_ENDPOINT = "https://ecom-api.costco.com/ebusiness/product/v1/products/graphql"

ONLINE_WAREHOUSE = "847"

QUERY_RECEIPTS = """
query receiptsWithCounts($startDate: String!, $endDate: String!, $documentType: String!, $documentSubType: String!) {
  receiptsWithCounts(startDate: $startDate, endDate: $endDate, documentType: $documentType, documentSubType: $documentSubType) {
    receipts {
      warehouseName warehouseShortName warehouseNumber warehouseCity warehouseState
      receiptType documentType transactionType
      transactionDateTime transactionDate transactionBarcode transactionNumber
      registerNumber total subTotal taxes instantSavings totalItemCount
      itemArray {
        itemNumber itemDescription01 itemDescription02 itemIdentifier
        itemDepartmentNumber transDepartmentNumber
        unit amount itemUnitPriceAmount taxFlag fuelUnitQuantity
      }
      tenderArray { tenderTypeCode tenderDescription tenderTypeName amountTender }
    }
  }
}
"""

QUERY_ONLINE_ORDERS = """
query getOnlineOrders($startDate: String!, $endDate: String!, $pageNumber: Int, $pageSize: Int, $warehouseNumber: String!) {
  getOnlineOrders(startDate: $startDate, endDate: $endDate, pageNumber: $pageNumber, pageSize: $pageSize, warehouseNumber: $warehouseNumber) {
    pageNumber pageSize totalNumberOfRecords
    bcOrders {
      orderHeaderId
      orderPlacedDate: orderedDate
      orderNumber: sourceOrderNumber
      orderTotal warehouseNumber status
      orderLineItems { itemId itemNumber itemDescription status shippingType }
    }
  }
}
"""

QUERY_ORDER_DETAILS = """
query getOrderDetails($orderNumbers: [String]) {
  getOrderDetails(orderNumbers: $orderNumbers) {
    orderNumber: sourceOrderNumber
    orderPlacedDate: orderedDate
    status merchandiseTotal retailDeliveryFee shippingAndHandling grocerySurcharge
    uSTaxTotal1 orderTotal
    shipToAddress: orderShipTos {
      orderLineItems {
        orderStatus itemNumber itemId
        itemDescription: sourceItemDescription
        price: unitPrice
        quantity: orderedTotalQuantity
        merchandiseTotalAmount programType returnStatus
      }
    }
  }
}
"""

QUERY_PRODUCTS = """
query products($clientId: String!, $itemNumbers: [String], $locale: [String], $warehouseNumber: String!) {
  products(clientId: $clientId, itemNumbers: $itemNumbers, locale: $locale, warehouseNumber: $warehouseNumber) {
    catalogData { itemNumber description { shortDescription } }
  }
}
"""


class CostcoAuthError(Exception):
    """The stored refresh token is missing, expired, or rejected."""


class CostcoAPIError(Exception):
    pass


def _error_detail(text: str) -> str:
    """Pull the backend's own message out of the API gateway's nested JSON error envelope."""
    try:
        value = json.loads(text)["context"]["statusMessage"]["description"]["value"]
    except (ValueError, KeyError, TypeError):
        return text
    try:
        return json.loads(value).get("errorMessage") or value
    except (ValueError, AttributeError):
        return value


def _token_expired(id_token: str | None, buffer_seconds: int = 120) -> bool:
    if not id_token:
        return True
    try:
        exp = jwt.decode(id_token, options={"verify_signature": False}).get("exp", 0)
    except jwt.DecodeError:
        return True
    return time.time() >= exp - buffer_seconds


def redeem_refresh_token(refresh_token: str, client_id: str | None = None) -> dict:
    """Trade a refresh token for a fresh id_token (and usually a rotated refresh token)."""
    resp = curl_requests.post(
        TOKEN_ENDPOINT,
        data={
            "client_id": client_id or MSAL_CLIENT_ID,
            "scope": "openid profile offline_access",
            "grant_type": "refresh_token",
            "refresh_token": refresh_token,
        },
        headers={
            "Content-Type": "application/x-www-form-urlencoded",
            "Origin": "https://www.costco.com",
            "Referer": "https://www.costco.com/",
            "User-Agent": USER_AGENT,
            "Accept": "*/*",
        },
        impersonate=IMPERSONATE,
        timeout=30,
    )
    if resp.status_code != 200:
        raise CostcoAuthError(f"token endpoint returned {resp.status_code}: {resp.text[:300]}")
    body = resp.json()
    if "id_token" not in body:
        raise CostcoAuthError(f"token endpoint returned no id_token: {list(body)}")
    return body


def _email_from(id_token: str) -> str | None:
    try:
        claims = jwt.decode(id_token, options={"verify_signature": False})
    except jwt.DecodeError:
        return None
    return claims.get("email") or (claims.get("emails") or [None])[0]


def link_account(refresh_token: str, client_id: str | None = None) -> str | None:
    """Validate a refresh token from the app and store it. Returns the account email."""
    tokens = redeem_refresh_token(refresh_token, client_id)
    email = _email_from(tokens["id_token"])
    now = db.now_iso()
    with db.session() as conn:
        conn.execute(
            "INSERT INTO costco_auth (id, refresh_token, client_id, id_token, email, linked_at, refreshed_at, last_error) "
            "VALUES (1, ?, ?, ?, ?, ?, ?, NULL) "
            "ON CONFLICT(id) DO UPDATE SET refresh_token = excluded.refresh_token, client_id = excluded.client_id, "
            "id_token = excluded.id_token, email = excluded.email, linked_at = excluded.linked_at, "
            "refreshed_at = excluded.refreshed_at, last_error = NULL",
            (tokens.get("refresh_token", refresh_token), client_id, tokens["id_token"], email, now, now),
        )
        # A fresh link clears any outstanding "please re-link" notices.
        conn.execute("UPDATE events SET dismissed = 1 WHERE kind IN ('relink_needed', 'relink_soon')")
    return email


class CostcoClient:
    def __init__(self) -> None:
        self._id_token: str | None = None

    def _bearer(self, force: bool = False) -> str:
        with db.session() as conn:
            row = conn.execute("SELECT * FROM costco_auth WHERE id = 1").fetchone()
            if not row or not row["refresh_token"]:
                raise CostcoAuthError("No Costco account linked")
            if not force and not _token_expired(row["id_token"]):
                return row["id_token"]
            try:
                tokens = redeem_refresh_token(row["refresh_token"], row["client_id"])
            except CostcoAuthError as e:
                conn.execute("UPDATE costco_auth SET last_error = ? WHERE id = 1", (str(e),))
                raise
            # Keep the rotated refresh token so the 90-day window slides forward.
            conn.execute(
                "UPDATE costco_auth SET id_token = ?, refresh_token = ?, refreshed_at = ?, last_error = NULL WHERE id = 1",
                (tokens["id_token"], tokens.get("refresh_token", row["refresh_token"]), db.now_iso()),
            )
            return tokens["id_token"]

    def _post(self, endpoint: str, query: str, variables: dict) -> dict:
        force = False
        for attempt in range(3):
            headers = {
                "Content-Type": "application/json-patch+json",
                "Accept": "*/*",
                "Origin": "https://www.costco.com",
                "Referer": "https://www.costco.com/",
                "User-Agent": USER_AGENT,
                "Accept-Language": "en-US,en;q=0.9",
                "costco-x-authorization": f"Bearer {self._bearer(force)}",
                "costco-x-wcs-clientid": WCS_CLIENT_ID,
                "client-identifier": CLIENT_IDENTIFIER,
                "costco.env": "ecom",
                "costco.service": "restOrders",
            }
            resp = curl_requests.post(
                endpoint,
                data=json.dumps({"query": query, "variables": variables}),
                headers=headers,
                impersonate=IMPERSONATE,
                timeout=60,
            )
            if resp.status_code == 401 and not force:
                force = True
                continue
            if resp.status_code in (502, 503, 504) and attempt < 2:
                time.sleep(2 ** attempt)
                continue
            if resp.status_code != 200:
                logger.warning(
                    "%s returned %s for variables %s: %s",
                    endpoint, resp.status_code, json.dumps(variables), resp.text[:5000],
                )
                raise CostcoAPIError(f"{endpoint} returned {resp.status_code}: {_error_detail(resp.text)[:500]}")
            body = resp.json()
            if body.get("errors") and not body.get("data"):
                raise CostcoAPIError(f"GraphQL errors: {json.dumps(body['errors'])[:300]}")
            return body.get("data") or {}
        raise CostcoAPIError(f"{endpoint}: gave up after retries")

    def list_receipts(self, start: str, end: str) -> list[dict]:
        """Receipts with full line items. Dates are M/D/YYYY."""
        data = self._post(ORDERS_ENDPOINT, QUERY_RECEIPTS, {
            "startDate": start, "endDate": end, "documentType": "all", "documentSubType": "all",
        })
        return (data.get("receiptsWithCounts") or {}).get("receipts") or []

    def list_online_orders(self, start: str, end: str) -> list[dict]:
        """All online orders in range, following pagination. Dates are YYYY-M-D."""
        orders: dict[str, dict] = {}
        page, page_size = 1, 50
        while True:
            data = self._post(ORDERS_ENDPOINT, QUERY_ONLINE_ORDERS, {
                "startDate": start, "endDate": end, "pageNumber": page,
                "pageSize": page_size, "warehouseNumber": ONLINE_WAREHOUSE,
            })
            payload = data.get("getOnlineOrders")
            if isinstance(payload, list):
                payload = payload[0] if payload else {}
            payload = payload or {}
            batch = payload.get("bcOrders") or []
            for order in batch:
                key = order.get("orderNumber") or order.get("orderHeaderId")
                if key:
                    orders.setdefault(str(key), order)
            total = int(payload.get("totalNumberOfRecords") or 0)
            if len(batch) < page_size or page * page_size >= total:
                return list(orders.values())
            page += 1

    def order_detail(self, order_number: str) -> dict | None:
        # Costco only accepts one order number per request.
        data = self._post(ORDERS_ENDPOINT, QUERY_ORDER_DETAILS, {"orderNumbers": [order_number]})
        payload = data.get("getOrderDetails")
        if isinstance(payload, list):
            return payload[0] if payload else None
        return payload

    def product_names(self, item_numbers: list[str], warehouse_number: str) -> dict[str, str]:
        names: dict[str, str] = {}
        for i in range(0, len(item_numbers), 20):
            data = self._post(PRODUCTS_ENDPOINT, QUERY_PRODUCTS, {
                "clientId": WCS_CLIENT_ID,
                "itemNumbers": item_numbers[i:i + 20],
                "locale": ["en-US"],
                "warehouseNumber": warehouse_number,
            })
            for item in (data.get("products") or {}).get("catalogData") or []:
                desc = (item.get("description") or {}).get("shortDescription")
                if desc:
                    names[str(item["itemNumber"])] = desc
        return names
