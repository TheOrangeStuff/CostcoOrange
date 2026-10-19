# Costco Tracker

Family Costco spending tracker: a Python backend on this machine that syncs from Costco, and an
Android app that shows the analysis. Built from ideas in
[ziningw/costco-receipt-analysis](https://github.com/ziningw/costco-receipt-analysis) (analysis) and
[thehesiod/costco-mcp](https://github.com/thehesiod/costco-mcp) (refresh-token auth, Chrome TLS
impersonation past Akamai).

Costco has no public API. This uses their undocumented internal GraphQL API and can break whenever
Costco changes their site. Personal/family use only.

## Backend (`backend/`)

FastAPI + SQLite in a Docker container on the Unraid server (192.168.1.4), on the same Docker
network as Nginx Proxy Manager, which serves it at `costco.eristhewerd.com`.

Every push to `main` touching `backend/` runs the tests and publishes
`ghcr.io/theorangestuff/costcoorange-backend:latest` (`.github/workflows/backend-image.yml`).
The package is private, so Unraid logs in to ghcr.io once with a classic GitHub token that has
only `read:packages` (fine-grained tokens can't pull from ghcr.io).

### Unraid setup (once)

1. Unraid terminal: `docker login ghcr.io -u TheOrangeStuff` (password: the `read:packages` token).
2. Save `unraid/my-costcoorange.xml` to the flash drive as
   `/boot/config/plugins/dockerMan/templates-user/my-costcoorange.xml`.
3. Docker → Add Container → Template: `costcoorange`. Set Network Type to the custom network NPM
   is on (the template says `proxynet`), check the appdata path and TZ, Apply.
   NPM proxy host `costco.eristhewerd.com` → scheme `http`, host `costcoorange`, port `7057`,
   with Block Common Exploits and your usual SSL setup.
4. Create family logins (runs as the container's user so the database stays writable):
   `docker exec -it -u 99:100 costcoorange python -m app.cli adduser <name>`

To update: Unraid Docker page → "Check for Updates" → apply. Logs: the container's log view.
`docker-compose.example.yml` shows the same settings as a compose file.

### Admin and development

```bash
docker exec -it -u 99:100 costcoorange python -m app.cli users
docker exec -it -u 99:100 costcoorange python -m app.cli sync          # sync now, foreground
docker exec -it -u 99:100 costcoorange python -m app.cli renormalize   # re-parse stored raw data

cd backend && ~/.local/bin/uv sync && ~/.local/bin/uv run pytest -q    # local tests
```

- Errors: server warnings/tracebacks and the app's error reports (failed API calls, crashes) land
  in the `logs` table. Read them with any family login:
  `curl -H "Authorization: Bearer <token>" https://costco.eristhewerd.com/api/logs?limit=50`
  (`&source=app` or `&source=server` to filter).
- Data lives in `/data/costco.db` (the appdata share). Back up that folder; never commit it. Raw
  Costco payloads are kept, so history accumulates past Costco's ~2-year window. Every distinct
  payload Costco sends also goes into the append-only `payloads` table, even ones that no longer
  parse, and an order refresh never blanks out stored values.
- The server snapshots the database daily to `/data/backups/costco-YYYY-MM-DD.db` (keeps
  `COSTCO_BACKUP_KEEP_DAYS`, default 30) and before any re-parse; `python -m app.cli backup`
  takes one on demand. These sit on the same disk, so also copy appdata off the box (e.g. the
  Appdata Backup plugin).
- The first sync backfills 2 years silently; later syncs (every 2 hours) re-scan 45 days and
  create events for new receipts, new online orders and price drops. The app polls
  `/api/events` and turns them into notifications.
- Costco sign-in: the app opens Costco's login page in a WebView, captures MSAL's refresh token
  from the site's own call to the B2C token endpoint (Costco no longer persists it in storage),
  and posts it to `/api/costco/link`. The B2C policy name changes occasionally; override it with
  `COSTCO_B2C_POLICY`. The server keeps it (and its rotations) and
  mints bearer tokens as needed. Expect to re-link roughly every 90 days; the server warns at 80.
- Settings are env vars on the container (see `app/config.py`).

## Android (`android/`)

Kotlin + Compose, same toolchain as WeRun.

```bash
cd android
export JAVA_HOME=~/.local/jdk-21
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

The login screen's server field defaults to `https://costco.eristhewerd.com`; use
`http://192.168.1.4:7057` on the LAN.

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
`COSTCO_DATA_DIR`) with a `costco_auth` row so the app doesn't jump to the link screen, run it on
another port, then
`DEMO_SERVER=http://127.0.0.1:8748 DEMO_TOKEN=<token> ./gradlew testDebugUnitTest`.

## Known gaps

- Price-adjustment alerts only see drops that show up on your own later receipts; checking
  Costco's current prices needs exploring their product API with a live token.
- Costco doesn't publish department names; label them in the app.
- Executive-reward math can't exclude tobacco/postage/services (receipts don't flag them).
