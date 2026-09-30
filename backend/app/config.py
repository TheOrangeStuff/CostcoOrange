"""Runtime settings, read from the environment (see backend/.env.example)."""

import os
from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parent.parent

DATA_DIR = Path(os.environ.get("COSTCO_DATA_DIR", BACKEND_DIR / "data"))
DB_PATH = DATA_DIR / "costco.db"

# How often the background sync runs.
SYNC_INTERVAL_MINUTES = int(os.environ.get("COSTCO_SYNC_INTERVAL_MINUTES", "120"))

# Costco only serves ~2 years of receipts; the first sync backfills this far.
BACKFILL_DAYS = int(os.environ.get("COSTCO_BACKFILL_DAYS", "730"))

# Routine syncs re-scan this window (receipts can post a day or two late,
# online orders change status for weeks).
RESCAN_DAYS = int(os.environ.get("COSTCO_RESCAN_DAYS", "45"))

# Costco's price adjustment policy window.
PRICE_ADJUST_DAYS = int(os.environ.get("COSTCO_PRICE_ADJUST_DAYS", "30"))

# Executive membership math (US, as of the Sept 2024 fee change).
EXEC_REWARD_RATE = float(os.environ.get("COSTCO_EXEC_REWARD_RATE", "0.02"))
EXEC_REWARD_CAP = float(os.environ.get("COSTCO_EXEC_REWARD_CAP", "1250"))
EXEC_UPGRADE_COST = float(os.environ.get("COSTCO_EXEC_UPGRADE_COST", "65"))

# Costco's refresh tokens last ~90 days; warn the family this many days before.
RELINK_WARN_DAYS = int(os.environ.get("COSTCO_RELINK_WARN_DAYS", "80"))
