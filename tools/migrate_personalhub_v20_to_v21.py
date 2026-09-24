#!/usr/bin/env python3
"""Create and validate schema 21 from a PersonalHub schema-20 database."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import tempfile
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
SQL_PATH = ROOT / "tools/migrations/personalhub_20_21.sql"
SCHEMA_21_PATH = ROOT / "core/database/schemas/com.gernalix.personalhub.core.database.PersonalHubDatabase/21.json"
SOURCE_VERSION = 20
TARGET_VERSION = 21
SOURCE_IDENTITY_HASH = "94ae44e094a5395f949fe614a44bec39"
TARGET_IDENTITY_HASH = "7b21745c7c548b03282ae48e9542b756"
HEALTH_TABLES = {
    "health_ai_evidence", "health_ai_snapshots", "health_events",
    "health_examinations", "health_import_batches", "health_journal_entries",
    "health_measurements", "health_samples", "health_source_metadata",
}
MUTATED_TABLES = {
    "room_master_table", "hub_generation", "hub_entity_bindings",
    "hub_context_members", "hub_contexts", "hub_context_type_fields",
    "hub_activity_log", "hub_sync_pending", "hub_sync_known", "hub_tags",
    "hub_tag_aliases", "hub_tag_assignments", "hub_tag_parents",
    "hub_saved_tag_filters",
}


class MigrationError(RuntimeError):
    pass


def quote(identifier: str) -> str:
    return '"' + identifier.replace('"', '""') + '"'


def scalar(db: sqlite3.Connection, sql: str, args: tuple[Any, ...] = ()) -> Any:
    row = db.execute(sql, args).fetchone()
    if row is None:
        raise MigrationError(f"query returned no rows: {sql}")
    return row[0]


def table_exists(db: sqlite3.Connection, table: str) -> bool:
    return db.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
        (table,),
    ).fetchone() is not None


def room_identity(db: sqlite3.Connection) -> str | None:
    if not table_exists(db, "room_master_table"):
        return None
    row = db.execute("SELECT identity_hash FROM room_master_table WHERE id=42").fetchone()
    return None if row is None else str(row[0])


def check_integrity(db: sqlite3.Connection, label: str) -> None:
    quick = db.execute("PRAGMA quick_check").fetchall()
    if quick != [("ok",)]:
        raise MigrationError(f"{label}: quick_check failed: {quick[:5]}")
    foreign = db.execute("PRAGMA foreign_key_check").fetchall()
    if foreign:
        raise MigrationError(f"{label}: foreign_key_check failed: {foreign[:5]}")


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def source_file_hashes(path: Path) -> dict[str, str]:
    return {
        candidate.name: file_sha256(candidate)
        for candidate in (Path(str(path) + suffix) for suffix in ("", "-wal", "-shm"))
        if candidate.is_file()
    }


def encode_value(value: Any) -> bytes:
    if value is None:
        return b"N"
    if isinstance(value, bytes):
        tag, payload = b"B", value
    elif isinstance(value, int):
        tag, payload = b"I", str(value).encode()
    elif isinstance(value, float):
        tag, payload = b"F", value.hex().encode()
    else:
        tag, payload = b"T", str(value).encode()
    return tag + len(payload).to_bytes(8, "big") + payload


def table_hash(db: sqlite3.Connection, table: str) -> str:
    info = db.execute(f"PRAGMA table_info({quote(table)})").fetchall()
    columns = [row[1] for row in info]
    primary = [
        row[1]
        for row in sorted((row for row in info if row[5] > 0), key=lambda row: row[5])
    ]
    sql = f"SELECT * FROM {quote(table)}"
    if primary or columns:
        sql += " ORDER BY " + ",".join(quote(column) for column in (primary or columns))
    digest = hashlib.sha256(table.encode())
    for row in db.execute(sql):
        digest.update(b"R")
        for value in row:
            digest.update(encode_value(value))
    return digest.hexdigest()


def preserved_table_hashes(db: sqlite3.Connection) -> dict[str, str]:
    names = [
        row[0]
        for row in db.execute(
            "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"
        )
        if not row[0].startswith("sqlite_")
    ]
    return {
        table: table_hash(db, table)
        for table in names
        if table not in MUTATED_TABLES and table not in HEALTH_TABLES
    }


def drop_runtime_triggers(db: sqlite3.Connection) -> list[str]:
    names = [
        row[0]
        for row in db.execute(
            "SELECT name FROM sqlite_master WHERE type='trigger' ORDER BY name"
        )
    ]
    for name in names:
        db.execute(f"DROP TRIGGER {quote(name)}")
    db.commit()
    return names


def count_if(db: sqlite3.Connection, table: str, predicate: str = "1") -> int:
    if not table_exists(db, table):
        return 0
    return int(scalar(db, f"SELECT count(*) FROM {quote(table)} WHERE {predicate}"))


def residue_counts(db: sqlite3.Connection) -> dict[str, int]:
    return {
        "health_tables": int(scalar(
            db,
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name LIKE 'health_%'",
        )),
        "health_views": int(scalar(
            db,
            "SELECT count(*) FROM sqlite_master WHERE type='view' AND name LIKE 'v_health_%'",
        )),
        "salute_bindings": count_if(db, "hub_entity_bindings", "module_id='salute'"),
        "salute_activity": count_if(
            db, "hub_activity_log", "module_id='salute' OR source_table LIKE 'health_%'"
        ),
        "salute_sync_pending": count_if(
            db, "hub_sync_pending", "table_name LIKE 'health_%'"
        ),
        "salute_sync_known": count_if(
            db, "hub_sync_known", "table_name LIKE 'health_%'"
        ),
        "salute_tags": count_if(
            db, "hub_tags", "namespace='salute' OR namespace LIKE 'salute.%'"
        ),
        "salute_saved_filters": count_if(
            db,
            "hub_saved_tag_filters",
            "namespace='salute' OR namespace LIKE 'salute.%'",
        ),
        "salute_context_fields": count_if(
            db, "hub_context_type_fields", "accepted_module_id='salute'"
        ),
    }


def validate_schema_21(db: sqlite3.Connection) -> None:
    schema = json.loads(SCHEMA_21_PATH.read_text(encoding="utf-8"))["database"]
    if int(scalar(db, "PRAGMA user_version")) != TARGET_VERSION:
        raise MigrationError("target user_version is not 21")
    if room_identity(db) != TARGET_IDENTITY_HASH:
        raise MigrationError(f"target Room identity mismatch: {room_identity(db)}")

    for entity in schema["entities"]:
        table = entity["tableName"]
        actual_rows = db.execute(f"PRAGMA table_info({quote(table)})").fetchall()
        if not actual_rows:
            raise MigrationError(f"missing target table: {table}")
        actual = {
            row[1]: (str(row[2]).upper(), bool(row[3]), int(row[5]))
            for row in actual_rows
        }
        primary = entity["primaryKey"]["columnNames"]
        expected: dict[str, tuple[str, bool, int]] = {}
        for field in entity["fields"]:
            column = field["columnName"]
            expected[column] = (
                field["affinity"],
                bool(field.get("notNull", False)),
                primary.index(column) + 1 if column in primary else 0,
            )
        if actual != expected:
            raise MigrationError(f"incompatible target columns: {table}")

        expected_foreign_keys: set[tuple[str, str, str, str, str]] = set()
        for foreign in entity.get("foreignKeys", []):
            for column, referenced in zip(
                foreign["columns"], foreign["referencedColumns"]
            ):
                expected_foreign_keys.add(
                    (
                        foreign["table"],
                        column,
                        referenced,
                        foreign["onUpdate"],
                        foreign["onDelete"],
                    )
                )
        actual_foreign_keys = {
            (row[2], row[3], row[4], row[5], row[6])
            for row in db.execute(f"PRAGMA foreign_key_list({quote(table)})")
        }
        if actual_foreign_keys != expected_foreign_keys:
            raise MigrationError(f"incompatible target foreign keys: {table}")

        actual_indexes = {
            row[1]: bool(row[2])
            for row in db.execute(f"PRAGMA index_list({quote(table)})")
        }
        for index in entity.get("indices", []):
            name = index["name"]
            if actual_indexes.get(name) != bool(index.get("unique", False)):
                raise MigrationError(f"missing or incompatible target index: {name}")
            columns = [
                row[2]
                for row in db.execute(f"PRAGMA index_info({quote(name)})")
            ]
            if columns != index["columnNames"]:
                raise MigrationError(f"incompatible target index columns: {name}")

    residue = residue_counts(db)
    if any(residue.values()):
        raise MigrationError(f"Salute residue remains: {residue}")
    check_integrity(db, "target")


def migrate(source: Path, output: Path) -> dict[str, Any]:
    source = source.expanduser().resolve()
    output = output.expanduser().resolve()
    if not source.is_file():
        raise MigrationError(f"source missing: {source}")
    if output.exists():
        raise MigrationError(f"output already exists: {output}")
    if source == output:
        raise MigrationError("source and output must differ")
    output.parent.mkdir(parents=True, exist_ok=True)

    source_db = sqlite3.connect("file:" + source.as_posix() + "?mode=ro", uri=True)
    staging: Path | None = None
    try:
        source_version = int(scalar(source_db, "PRAGMA user_version"))
        if source_version != SOURCE_VERSION:
            raise MigrationError(
                f"expected schema {SOURCE_VERSION}, found schema {source_version}"
            )
        if room_identity(source_db) != SOURCE_IDENTITY_HASH:
            raise MigrationError(
                f"source Room identity mismatch: {room_identity(source_db)}"
            )
        check_integrity(source_db, "source")
        preserved_before = preserved_table_hashes(source_db)
        before_residue = residue_counts(source_db)
        hashes = source_file_hashes(source)

        fd, name = tempfile.mkstemp(
            prefix=".personalhub-v21-", suffix=".db", dir=output.parent
        )
        os.close(fd)
        staging = Path(name)
        staging.unlink()
        target_db = sqlite3.connect(staging)
        try:
            source_db.backup(target_db)
            target_db.execute("PRAGMA journal_mode=DELETE")
            dropped_triggers = drop_runtime_triggers(target_db)
            target_db.executescript(SQL_PATH.read_text(encoding="utf-8"))
            target_db.execute("PRAGMA journal_mode=DELETE")
            validate_schema_21(target_db)
            preserved_after = preserved_table_hashes(target_db)
            if preserved_before != preserved_after:
                changed = sorted(
                    table
                    for table in set(preserved_before) | set(preserved_after)
                    if preserved_before.get(table) != preserved_after.get(table)
                )
                raise MigrationError(
                    f"non-Salute data changed unexpectedly: {changed}"
                )
            after_residue = residue_counts(target_db)
            target_db.commit()
        finally:
            target_db.close()

        with staging.open("rb") as stream:
            os.fsync(stream.fileno())
        os.replace(staging, output)
        staging = None
        report = {
            "status": "PASS",
            "source": str(source),
            "output": str(output),
            "source_version": SOURCE_VERSION,
            "target_version": TARGET_VERSION,
            "source_identity_hash": SOURCE_IDENTITY_HASH,
            "target_identity_hash": TARGET_IDENTITY_HASH,
            "source_files_sha256": hashes,
            "output_sha256": file_sha256(output),
            "preserved_tables_verified": len(preserved_before),
            "dropped_runtime_triggers": dropped_triggers,
            "residue_before": before_residue,
            "residue_after": after_residue,
            "quick_check": "ok",
            "foreign_key_check": [],
        }
        Path(str(output) + ".validation.json").write_text(
            json.dumps(report, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        return report
    finally:
        source_db.close()
        if staging is not None:
            staging.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = migrate(args.source, args.output)
    except Exception as error:
        print(json.dumps(
            {"status": "FAIL", "error": f"{type(error).__name__}: {error}"},
            sort_keys=True,
        ))
        return 2
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
