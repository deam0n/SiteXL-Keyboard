from __future__ import annotations

import argparse
import hashlib
import io
import os
import re
import secrets
import sqlite3
import stat
import sys
import time
import zipfile
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator

from fastapi import Depends, FastAPI, Header, HTTPException, Request, Response
from pydantic import BaseModel, Field


DATA_DIR = Path(os.environ.get("DATA_DIR", "/data"))
DB_PATH = DATA_DIR / "sitexl-sync.sqlite3"
MAX_SNAPSHOT_BYTES = int(os.environ.get("MAX_SNAPSHOT_BYTES", str(25 * 1024 * 1024)))
MAX_ZIP_ENTRIES = 2500
MAX_VOCABULARY_WORDS_PER_REQUEST = 100000
DEVICE_ID_RE = re.compile(r"^[A-Za-z0-9_-]{8,64}$")
LOCALE_RE = re.compile(r"^[A-Za-z0-9_-]{2,40}$")

app = FastAPI(
    title="SiteXL Keyboard Sync API",
    description="Private sync API for SiteXL Keyboard settings and learned vocabulary.",
    version="1.0.0",
)


@contextmanager
def database() -> Iterator[sqlite3.Connection]:
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(DB_PATH, timeout=20)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys = ON")
    connection.execute("PRAGMA journal_mode = WAL")
    try:
        yield connection
        connection.commit()
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


def initialize_database() -> None:
    with database() as db:
        db.executescript(
            """
            CREATE TABLE IF NOT EXISTS users (
                user_id TEXT PRIMARY KEY,
                created_at INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS api_tokens (
                token_hash TEXT PRIMARY KEY,
                user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
                created_at INTEGER NOT NULL,
                revoked_at INTEGER
            );

            CREATE TABLE IF NOT EXISTS settings_snapshots (
                user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
                device_id TEXT NOT NULL,
                device_name TEXT NOT NULL,
                schema_version INTEGER NOT NULL DEFAULT 1,
                app_version TEXT NOT NULL DEFAULT '',
                sha256 TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                payload BLOB NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (user_id, device_id)
            );

            CREATE TABLE IF NOT EXISTS vocabulary (
                user_id TEXT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
                locale TEXT NOT NULL,
                word TEXT NOT NULL,
                frequency INTEGER NOT NULL DEFAULT 0,
                timestamp INTEGER NOT NULL DEFAULT -1,
                is_not_a_word INTEGER NOT NULL DEFAULT 0,
                is_possibly_offensive INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (user_id, locale, word)
            );

            CREATE INDEX IF NOT EXISTS idx_snapshots_user_updated
                ON settings_snapshots(user_id, updated_at DESC);
            CREATE INDEX IF NOT EXISTS idx_vocabulary_user_locale
                ON vocabulary(user_id, locale);
            """
        )


@app.on_event("startup")
def on_startup() -> None:
    initialize_database()


def validate_device_id(device_id: str) -> str:
    if not DEVICE_ID_RE.fullmatch(device_id):
        raise HTTPException(status_code=400, detail="Invalid device ID")
    return device_id


def authenticated_user(authorization: str | None = Header(default=None)) -> str:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Bearer token required")
    token = authorization[7:].strip()
    if len(token) < 32 or len(token) > 256:
        raise HTTPException(status_code=401, detail="Invalid token")
    token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
    with database() as db:
        row = db.execute(
            "SELECT user_id FROM api_tokens WHERE token_hash = ? AND revoked_at IS NULL",
            (token_hash,),
        ).fetchone()
    if row is None:
        raise HTTPException(status_code=401, detail="Invalid or revoked token")
    return str(row["user_id"])


def validate_snapshot(payload: bytes) -> None:
    if not payload:
        raise HTTPException(status_code=400, detail="Empty snapshot")
    if len(payload) > MAX_SNAPSHOT_BYTES:
        raise HTTPException(status_code=413, detail="Snapshot exceeds configured size limit")
    try:
        with zipfile.ZipFile(io.BytesIO(payload)) as archive:
            entries = archive.infolist()
            if not entries or len(entries) > MAX_ZIP_ENTRIES:
                raise HTTPException(status_code=400, detail="Invalid snapshot entry count")
            total_uncompressed = 0
            for entry in entries:
                name = entry.filename
                parts = Path(name).parts
                unix_mode = (entry.external_attr >> 16) & 0xFFFF
                if (
                    not name
                    or name.startswith(("/", "\\"))
                    or "\\" in name
                    or any(part in ("", ".", "..") for part in parts)
                    or stat.S_ISLNK(unix_mode)
                ):
                    raise HTTPException(status_code=400, detail="Unsafe snapshot entry")
                if name not in ("preferences.json", "protected_preferences.json") and not (
                    name.startswith("files/") or name.startswith("device/")
                ):
                    raise HTTPException(status_code=400, detail="Unexpected snapshot entry")
                total_uncompressed += entry.file_size
                if total_uncompressed > MAX_SNAPSHOT_BYTES * 4:
                    raise HTTPException(status_code=413, detail="Uncompressed snapshot is too large")
                if entry.compress_size == 0 and entry.file_size > 0:
                    raise HTTPException(status_code=400, detail="Invalid compressed entry")
                if entry.compress_size and entry.file_size / entry.compress_size > 250:
                    raise HTTPException(status_code=400, detail="Suspicious compression ratio")
    except zipfile.BadZipFile as exc:
        raise HTTPException(status_code=400, detail="Snapshot must be a valid ZIP archive") from exc


class VocabularyWord(BaseModel):
    word: str = Field(min_length=1, max_length=80)
    frequency: int = Field(ge=0, le=255)
    timestamp: int = Field(ge=-1, le=2_147_483_647)
    is_not_a_word: bool = False
    is_possibly_offensive: bool = False


class VocabularyPayload(BaseModel):
    locales: dict[str, list[VocabularyWord]]


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "service": "sitexl-sync"}


@app.get("/api/v1/sync/settings")
def list_settings_snapshots(user_id: str = Depends(authenticated_user)) -> dict:
    with database() as db:
        rows = db.execute(
            """
            SELECT device_id, device_name, schema_version, app_version,
                   sha256, size_bytes, updated_at
            FROM settings_snapshots
            WHERE user_id = ?
            ORDER BY updated_at DESC
            """,
            (user_id,),
        ).fetchall()
    snapshots = [dict(row) for row in rows]
    return {
        "snapshots": snapshots,
        "latest_device_id": snapshots[0]["device_id"] if snapshots else None,
    }


@app.put("/api/v1/sync/settings/{device_id}")
async def put_settings_snapshot(
    device_id: str,
    request: Request,
    user_id: str = Depends(authenticated_user),
    x_device_name: str = Header(default="Android device"),
    x_app_version: str = Header(default=""),
    x_schema_version: int = Header(default=1),
) -> dict:
    validate_device_id(device_id)
    if x_schema_version != 1:
        raise HTTPException(status_code=400, detail="Unsupported snapshot schema version")
    declared_length = request.headers.get("content-length")
    if declared_length and int(declared_length) > MAX_SNAPSHOT_BYTES:
        raise HTTPException(status_code=413, detail="Snapshot exceeds configured size limit")
    payload = await request.body()
    validate_snapshot(payload)
    digest = hashlib.sha256(payload).hexdigest()
    now = int(time.time())
    device_name = x_device_name.strip()[:80] or "Android device"
    app_version = x_app_version.strip()[:40]
    with database() as db:
        db.execute(
            """
            INSERT INTO settings_snapshots
                (user_id, device_id, device_name, schema_version, app_version,
                 sha256, size_bytes, payload, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(user_id, device_id) DO UPDATE SET
                device_name=excluded.device_name,
                schema_version=excluded.schema_version,
                app_version=excluded.app_version,
                sha256=excluded.sha256,
                size_bytes=excluded.size_bytes,
                payload=excluded.payload,
                updated_at=excluded.updated_at
            """,
            (
                user_id, device_id, device_name, x_schema_version, app_version,
                digest, len(payload), sqlite3.Binary(payload), now,
            ),
        )
    return {
        "device_id": device_id,
        "device_name": device_name,
        "sha256": digest,
        "size_bytes": len(payload),
        "updated_at": now,
    }


@app.get("/api/v1/sync/settings/{device_id}")
def get_settings_snapshot(
    device_id: str,
    user_id: str = Depends(authenticated_user),
) -> Response:
    validate_device_id(device_id)
    with database() as db:
        row = db.execute(
            """
            SELECT payload, sha256, device_name
            FROM settings_snapshots
            WHERE user_id = ? AND device_id = ?
            """,
            (user_id, device_id),
        ).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="No settings snapshot for this device")
    return Response(
        content=bytes(row["payload"]),
        media_type="application/zip",
        headers={
            "ETag": f'"{row["sha256"]}"',
            "Content-Disposition": f'attachment; filename="sitexl-settings-{device_id}.zip"',
            "X-Device-Name": row["device_name"],
        },
    )


@app.delete("/api/v1/sync/settings/{device_id}")
def delete_settings_snapshot(
    device_id: str,
    user_id: str = Depends(authenticated_user),
) -> dict[str, bool]:
    validate_device_id(device_id)
    with database() as db:
        cursor = db.execute(
            "DELETE FROM settings_snapshots WHERE user_id = ? AND device_id = ?",
            (user_id, device_id),
        )
    return {"deleted": cursor.rowcount > 0}


@app.put("/api/v1/sync/vocabulary/{device_id}")
def merge_vocabulary(
    device_id: str,
    payload: VocabularyPayload,
    user_id: str = Depends(authenticated_user),
) -> dict[str, int]:
    validate_device_id(device_id)
    total = 0
    for locale, words in payload.locales.items():
        if not LOCALE_RE.fullmatch(locale):
            raise HTTPException(status_code=400, detail=f"Invalid locale: {locale}")
        total += len(words)
        if total > MAX_VOCABULARY_WORDS_PER_REQUEST:
            raise HTTPException(status_code=413, detail="Too many vocabulary entries")
    now = int(time.time())
    with database() as db:
        for locale, words in payload.locales.items():
            for item in words:
                word = item.word.strip()
                if not word or any(ord(char) < 32 for char in word):
                    continue
                db.execute(
                    """
                    INSERT INTO vocabulary
                        (user_id, locale, word, frequency, timestamp,
                         is_not_a_word, is_possibly_offensive, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(user_id, locale, word) DO UPDATE SET
                        frequency=MAX(vocabulary.frequency, excluded.frequency),
                        timestamp=MAX(vocabulary.timestamp, excluded.timestamp),
                        is_not_a_word=MAX(vocabulary.is_not_a_word, excluded.is_not_a_word),
                        is_possibly_offensive=MAX(
                            vocabulary.is_possibly_offensive, excluded.is_possibly_offensive
                        ),
                        updated_at=excluded.updated_at
                    """,
                    (
                        user_id, locale, word, item.frequency, item.timestamp,
                        int(item.is_not_a_word), int(item.is_possibly_offensive), now,
                    ),
                )
    return {"merged_words": total}


@app.get("/api/v1/sync/vocabulary")
def get_vocabulary(user_id: str = Depends(authenticated_user)) -> dict:
    with database() as db:
        rows = db.execute(
            """
            SELECT locale, word, frequency, timestamp,
                   is_not_a_word, is_possibly_offensive
            FROM vocabulary
            WHERE user_id = ?
            ORDER BY locale, frequency DESC, word
            """,
            (user_id,),
        ).fetchall()
    locales: dict[str, list[dict]] = {}
    for row in rows:
        locales.setdefault(row["locale"], []).append(
            {
                "word": row["word"],
                "frequency": row["frequency"],
                "timestamp": row["timestamp"],
                "is_not_a_word": bool(row["is_not_a_word"]),
                "is_possibly_offensive": bool(row["is_possibly_offensive"]),
            }
        )
    return {"locales": locales}


def create_user(user_id: str) -> None:
    if not re.fullmatch(r"[A-Za-z0-9_.@-]{1,80}", user_id):
        raise SystemExit("User ID may contain letters, numbers, '.', '_', '@' and '-' (max 80)")
    token = secrets.token_urlsafe(36)
    digest = hashlib.sha256(token.encode("utf-8")).hexdigest()
    now = int(time.time())
    with database() as db:
        db.execute(
            "INSERT OR IGNORE INTO users(user_id, created_at) VALUES (?, ?)",
            (user_id, now),
        )
        db.execute(
            "INSERT INTO api_tokens(token_hash, user_id, created_at) VALUES (?, ?, ?)",
            (digest, user_id, now),
        )
    print(f"User: {user_id}")
    print("Bearer token (shown once; save it in SiteXL Keyboard):")
    print(token)


def revoke_token(token: str) -> None:
    digest = hashlib.sha256(token.encode("utf-8")).hexdigest()
    with database() as db:
        cursor = db.execute(
            "UPDATE api_tokens SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL",
            (int(time.time()), digest),
        )
    print("Token revoked." if cursor.rowcount else "No active token matched.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="SiteXL Sync administration")
    subparsers = parser.add_subparsers(dest="command", required=True)
    create = subparsers.add_parser("create-user", help="Create a user and issue an API token")
    create.add_argument("user_id")
    revoke = subparsers.add_parser("revoke-token", help="Revoke a previously issued token")
    revoke.add_argument("token")
    arguments = parser.parse_args()
    if arguments.command == "create-user":
        initialize_database()
        create_user(arguments.user_id)
    elif arguments.command == "revoke-token":
        initialize_database()
        revoke_token(arguments.token)
