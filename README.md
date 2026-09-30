# Costco Tracker

Family Costco spending tracker: a Python backend on this machine that syncs from Costco, and an
Android app that shows the analysis. Built from ideas in
[ziningw/costco-receipt-analysis](https://github.com/ziningw/costco-receipt-analysis) (analysis) and
[thehesiod/costco-mcp](https://github.com/thehesiod/costco-mcp) (refresh-token auth, Chrome TLS
impersonation past Akamai).

Costco has no public API. This uses their undocumented internal GraphQL API and can break whenever
Costco changes their site. Personal/family use only.

## Backend (`backend/`)

FastAPI + SQLite, runs as a systemd **user** service on port **8747** (linger is enabled, so it runs
without a login). Nginx Proxy Manager fronts it at `costco.eristhewerd.com`.

```bash
cd backend
~/.local/bin/uv sync                                   # deps into .venv
~/.local/bin/uv run pytest -q                          # tests
~/.local/bin/uv run python -m app.cli adduser <name>   # family login (prompts for password)
~/.local/bin/uv run python -m app.cli sync             # sync now, in the foreground
systemctl --user restart costco-backend                # after code changes
journalctl --user -u costco-backend -f                 # logs
```

- Data lives in `backend/data/costco.db` (gitignored; never commit it). Raw Costco payloads are
  kept, so history accumulates past Costco's ~2-year window, and `app.cli renormalize` can
  re-parse them after a parsing fix.
- The first sync backfills 2 years silently; later syncs (every 2 hours) re-scan 45 days and
  create events for new receipts, new online orders and price drops. The app polls
  `/api/events` and turns them into notifications.
- Costco sign-in: the app opens Costco's login page in a WebView, reads MSAL's refresh token from
  localStorage, and posts it to `/api/costco/link`. The server keeps it (and its rotations) and
  mints bearer tokens as needed. Expect to re-link roughly every 90 days; the server warns at 80.
- Settings are env vars in `backend/.env` (see `app/config.py`).

## Android (`android/`)

Kotlin + Compose, same toolchain as WeRun.

```bash
cd android
export JAVA_HOME=~/.local/jdk-21
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

The login screen's server field defaults to `https://costco.eristhewerd.com`; use
`http://192.168.20.100:8747` on the LAN before the proxy is set up.

### Releases (Obtainium)

`.github/workflows/android-release.yml` runs on every push to `main` touching `android/`: it builds
a signed APK and publishes a release (next minor version, `costcoorange-vX.Y.0.apk`) on this
private repo. In Obtainium, add `https://github.com/TheOrangeStuff/CostcoOrange` and give it a
GitHub token that can read this repo (fine-grained PAT: this repo only, Contents: read-only).

The signing key is `~/.config/costco/release.jks` with passwords in `~/.config/costco/signing.env`.
Back it up: losing it means every phone must uninstall to update. Local signed build:
`set -a; . ~/.config/costco/signing.env; set +a; ./gradlew assembleRelease`.

### Screenshots against fake data

`ScreenshotTest` renders the real screens with Robolectric/Roborazzi into
`app/build/outputs/roborazzi/`. It needs a backend with data: seed a throwaway DB (separate
`COSTCO_DATA_DIR`), run it on another port, then
`DEMO_SERVER=http://127.0.0.1:8748 DEMO_TOKEN=<token> ./gradlew testDebugUnitTest`.

## Known gaps

- Price-adjustment alerts only see drops that show up on your own later receipts; checking
  Costco's current prices needs exploring their product API with a live token.
- Costco doesn't publish department names; label them in the app.
- Executive-reward math can't exclude tobacco/postage/services (receipts don't flag them).
