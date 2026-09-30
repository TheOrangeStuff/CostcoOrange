"""Family logins: username/password -> long-lived bearer token per device."""

import hashlib
import secrets
import time
from collections import defaultdict

import bcrypt
from fastapi import Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app import db

_bearer = HTTPBearer(auto_error=False)

# Slow down password guessing; the API is reachable from the internet.
MAX_FAILURES = 10
FAILURE_WINDOW = 15 * 60
_failures: dict[str, list[float]] = defaultdict(list)


def _hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def hash_password(password: str) -> str:
    return bcrypt.hashpw(password.encode(), bcrypt.gensalt()).decode()


def create_user(username: str, password: str) -> None:
    with db.session() as conn:
        conn.execute(
            "INSERT INTO users (username, pw_hash, created_at) VALUES (?, ?, ?) "
            "ON CONFLICT(username) DO UPDATE SET pw_hash = excluded.pw_hash",
            (username, hash_password(password), db.now_iso()),
        )


def login(username: str, password: str, device: str | None) -> str:
    key = username.lower()
    recent = [t for t in _failures[key] if time.time() - t < FAILURE_WINDOW]
    _failures[key] = recent
    if len(recent) >= MAX_FAILURES:
        raise HTTPException(429, "Too many attempts; try again later")

    with db.session() as conn:
        user = conn.execute("SELECT * FROM users WHERE username = ?", (username,)).fetchone()
        if not user or not bcrypt.checkpw(password.encode(), user["pw_hash"].encode()):
            _failures[key].append(time.time())
            raise HTTPException(401, "Wrong username or password")
        token = secrets.token_urlsafe(32)
        now = db.now_iso()
        conn.execute(
            "INSERT INTO sessions (token_hash, user_id, device, created_at, last_seen) VALUES (?, ?, ?, ?, ?)",
            (_hash_token(token), user["id"], device, now, now),
        )
    _failures.pop(key, None)
    return token


def logout(token: str) -> None:
    with db.session() as conn:
        conn.execute("DELETE FROM sessions WHERE token_hash = ?", (_hash_token(token),))


def current_user(creds: HTTPAuthorizationCredentials | None = Depends(_bearer)) -> dict:
    if not creds:
        raise HTTPException(401, "Not signed in")
    with db.session() as conn:
        row = conn.execute(
            "SELECT u.id, u.username FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ?",
            (_hash_token(creds.credentials),),
        ).fetchone()
        if not row:
            raise HTTPException(401, "Not signed in")
        conn.execute(
            "UPDATE sessions SET last_seen = ? WHERE token_hash = ?",
            (db.now_iso(), _hash_token(creds.credentials)),
        )
    return {"id": row["id"], "username": row["username"], "token": creds.credentials}
