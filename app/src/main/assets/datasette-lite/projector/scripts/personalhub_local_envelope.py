from __future__ import annotations
import base64, hashlib, json, sqlite3, uuid
from datetime import datetime, timezone
from pathlib import Path

EXCLUDED = {
    "room_master_table", "android_metadata", "hub_generation", "hub_sync_pending", "hub_sync_known",
    "sync_queue", "sync_shadow", "sync_meta", "hub_activity_undo_context", "hub_git_pending",
    "hub_git_events", "hub_git_edit_context", "hub_git_applied_patches", "hub_git_history_index",
    "hub_git_history_field_stats", "since_when_migration_state",
}
DEVICE_ID = "00000000-0000-0000-0000-000000000001"
UPDATED_MS = 1
UPDATED_ISO = "1970-01-01T00:00:00.001Z"

SCHEMA_SQL = """
CREATE TABLE personalhub_entities(
 sync_id TEXT PRIMARY KEY, device_id TEXT NOT NULL, entity_type TEXT NOT NULL,
 local_id TEXT NOT NULL, payload_json TEXT NOT NULL, updated_at_ms INTEGER NOT NULL,
 updated_at TEXT NOT NULL, deleted_at_ms INTEGER, deleted_at TEXT, source_app_version TEXT NOT NULL,
 UNIQUE(device_id, entity_type, local_id));
CREATE TABLE personalhub_projection_clock(id INTEGER PRIMARY KEY, epoch TEXT NOT NULL, revision INTEGER NOT NULL);
CREATE TABLE personalhub_projection_changes(sync_id TEXT PRIMARY KEY, revision INTEGER NOT NULL);
"""

def _quote_key(db, value):
    return db.execute("SELECT hex(quote(?))", (value,)).fetchone()[0]

def _sync_id(device: str, table: str, local_id: str) -> str:
    digest = hashlib.md5(f"personalhub:{device}:{table}:{local_id}".encode()).digest()
    return str(uuid.UUID(bytes=digest, version=3))

def _payload(row: sqlite3.Row):
    out = {}
    for key in row.keys():
        value = row[key]
        if isinstance(value, bytes):
            value = {"encoding": "base64", "data": base64.b64encode(value).decode()}
        out[key] = value
    return out

def build_local_envelope(raw_db, envelope_db, *, device_id=DEVICE_ID, app_version="offline"):
    raw_db, envelope_db = Path(raw_db), Path(envelope_db)
    if envelope_db.exists(): envelope_db.unlink()
    with sqlite3.connect(f"file:{raw_db.resolve()}?mode=ro", uri=True) as source, sqlite3.connect(envelope_db) as target:
        source.row_factory = sqlite3.Row
        target.executescript(SCHEMA_SQL)
        target.execute("INSERT INTO personalhub_projection_clock VALUES(1,?,1)", ("offline",))
        tables = [r[0] for r in source.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name") if r[0] not in EXCLUDED]
        inserted = 0
        for table in tables:
            info = list(source.execute(f"PRAGMA table_info(`{table}`)"))
            keys = [r[1] for r in sorted((r for r in info if r[5] > 0), key=lambda r:r[5])]
            if not keys:
                continue
            for row in source.execute(f"SELECT * FROM `{table}`"):
                local_id = ":".join(_quote_key(source, row[k]) for k in keys)
                sync_id = _sync_id(device_id, table, local_id)
                payload = _payload(row)
                deleted = row["deleted_at_ms"] if "deleted_at_ms" in row.keys() and row["deleted_at_ms"] is not None else None
                if deleted is None and table == "contacts" and "deleted_at" in row.keys() and row["deleted_at"] is not None:
                    deleted = row["deleted_at"]
                deleted_iso = datetime.fromtimestamp(deleted / 1000, timezone.utc).isoformat().replace("+00:00", "Z") if deleted is not None else None
                target.execute("INSERT INTO personalhub_entities VALUES(?,?,?,?,?,?,?,?,?,?)", (
                    sync_id, device_id, table, local_id, json.dumps(payload, separators=(",",":")),
                    UPDATED_MS, UPDATED_ISO, deleted, deleted_iso, app_version,
                ))
                target.execute("INSERT INTO personalhub_projection_changes VALUES(?,1)", (sync_id,))
                inserted += 1
        target.commit()
    return inserted
