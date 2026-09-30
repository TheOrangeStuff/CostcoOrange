"""SQLite storage. Raw Costco payloads are kept verbatim so history survives
past Costco's 2-year window and can be re-parsed if normalization changes."""

import sqlite3
from contextlib import contextmanager
from datetime import datetime, timezone

from app import config

SCHEMA = """
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY,
    username TEXT UNIQUE NOT NULL,
    pw_hash TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
    token_hash TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device TEXT,
    created_at TEXT NOT NULL,
    last_seen TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS costco_auth (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    refresh_token TEXT,
    client_id TEXT,
    id_token TEXT,
    email TEXT,
    linked_at TEXT,
    refreshed_at TEXT,
    last_error TEXT
);
CREATE TABLE IF NOT EXISTS receipts (
    barcode TEXT PRIMARY KEY,
    txn_date TEXT NOT NULL,
    txn_datetime TEXT,
    channel TEXT NOT NULL,              -- warehouse | gas
    warehouse_number TEXT,
    warehouse_name TEXT,
    total REAL NOT NULL,
    subtotal REAL,
    taxes REAL,
    raw TEXT NOT NULL,
    first_seen TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS receipts_date ON receipts(txn_date);
CREATE TABLE IF NOT EXISTS receipt_items (
    barcode TEXT NOT NULL REFERENCES receipts(barcode) ON DELETE CASCADE,
    line INTEGER NOT NULL,
    item_number TEXT NOT NULL,
    description TEXT,
    department INTEGER,
    units REAL NOT NULL,
    amount REAL NOT NULL,               -- shelf amount, before instant savings
    unit_price REAL,
    discount REAL NOT NULL DEFAULT 0,   -- instant savings applied to this line (<= 0)
    is_gas INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (barcode, line)
);
CREATE INDEX IF NOT EXISTS receipt_items_item ON receipt_items(item_number);
CREATE TABLE IF NOT EXISTS online_orders (
    order_number TEXT PRIMARY KEY,
    order_date TEXT NOT NULL,
    total REAL NOT NULL,
    merchandise_total REAL,
    status TEXT,
    raw TEXT NOT NULL,
    detail TEXT,
    first_seen TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS online_orders_date ON online_orders(order_date);
CREATE TABLE IF NOT EXISTS online_items (
    order_number TEXT NOT NULL REFERENCES online_orders(order_number) ON DELETE CASCADE,
    line INTEGER NOT NULL,
    item_number TEXT,
    description TEXT,
    unit_price REAL,
    quantity REAL,
    amount REAL,
    PRIMARY KEY (order_number, line)
);
CREATE TABLE IF NOT EXISTS products (
    item_number TEXT PRIMARY KEY,
    name TEXT,
    looked_up_at TEXT
);
CREATE TABLE IF NOT EXISTS departments (
    number INTEGER PRIMARY KEY,
    label TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    kind TEXT NOT NULL,                 -- new_receipt | new_order | price_drop | relink_needed | relink_soon
    ref TEXT NOT NULL,
    title TEXT NOT NULL,
    body TEXT,
    created_at TEXT NOT NULL,
    dismissed INTEGER NOT NULL DEFAULT 0,
    UNIQUE (kind, ref)
);
CREATE TABLE IF NOT EXISTS sync_runs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    started_at TEXT NOT NULL,
    finished_at TEXT,
    ok INTEGER,
    new_receipts INTEGER,
    new_orders INTEGER,
    error TEXT
);
CREATE TABLE IF NOT EXISTS state (
    key TEXT PRIMARY KEY,
    value TEXT
);
"""


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def connect() -> sqlite3.Connection:
    config.DATA_DIR.mkdir(parents=True, exist_ok=True, mode=0o700)
    conn = sqlite3.connect(config.DB_PATH, timeout=30, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA journal_mode = WAL")
    return conn


@contextmanager
def session():
    conn = connect()
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def init() -> None:
    with session() as conn:
        conn.executescript(SCHEMA)


def get_state(conn: sqlite3.Connection, key: str) -> str | None:
    row = conn.execute("SELECT value FROM state WHERE key = ?", (key,)).fetchone()
    return row["value"] if row else None


def set_state(conn: sqlite3.Connection, key: str, value: str) -> None:
    conn.execute(
        "INSERT INTO state (key, value) VALUES (?, ?) "
        "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        (key, value),
    )


def add_event(conn: sqlite3.Connection, kind: str, ref: str, title: str, body: str = "") -> bool:
    """Record a notification once per (kind, ref). Returns True if it was new."""
    cur = conn.execute(
        "INSERT OR IGNORE INTO events (kind, ref, title, body, created_at) VALUES (?, ?, ?, ?, ?)",
        (kind, ref, title, body, now_iso()),
    )
    return cur.rowcount > 0
