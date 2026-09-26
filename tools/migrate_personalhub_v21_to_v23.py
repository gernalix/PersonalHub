#!/usr/bin/env python3
"""Copy and validate a schema 21/22 PersonalHub database as schema 23."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = ROOT / "core/database/schemas/com.gernalix.personalhub.core.database.PersonalHubDatabase"
ADDED = {
    22: {"since_when_counters", "since_when_migration_state"},
    23: {"finance_owned_items", "finance_photo_index"},
}
LEGACY_MIGRATION_STATE_TABLE = "since_when_migration_state"
LEGACY_TRIGGER_PREFIXES = {
    "hub_dirty": "UPDATE hub_generation",
    "hub_sync": "hub_sync_pending",
    "hub_git_dirty": "hub_git_pending",
}


def schema(version: int) -> dict:
    return json.loads((SCHEMAS / f"{version}.json").read_text())["database"]


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def check(db: sqlite3.Connection) -> None:
    for name in ("quick_check", "integrity_check"):
        if db.execute(f"PRAGMA {name}").fetchall() != [("ok",)]:
            raise ValueError(f"{name} failed")
    if db.execute("PRAGMA foreign_key_check").fetchone():
        raise ValueError("foreign_key_check failed")


def snapshot(db: sqlite3.Connection) -> dict[str, tuple[int, str]]:
    result = {}
    for (table,) in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"):
        if table == "room_master_table":
            continue
        content = hashlib.sha256()
        count = 0
        for row in db.execute(f'SELECT * FROM "{table}" ORDER BY rowid'):
            content.update(repr(row).encode("utf-8", "backslashreplace"))
            count += 1
        result[table] = (count, content.hexdigest())
    return result


def validate(db: sqlite3.Connection, version: int) -> None:
    target = schema(version)
    if db.execute("PRAGMA user_version").fetchone()[0] != version:
        raise ValueError("schema version mismatch")
    if db.execute("SELECT identity_hash FROM room_master_table WHERE id=42").fetchone() != (target["identityHash"],):
        raise ValueError("Room identity mismatch")
    for entity in target["entities"]:
        table = entity["tableName"]
        actual = {row[1]: (row[2].upper(), bool(row[3]), row[5]) for row in db.execute(f'PRAGMA table_info("{table}")')}
        pk = entity["primaryKey"]["columnNames"]
        expected = {field["columnName"]: (field["affinity"], bool(field.get("notNull", False)), pk.index(field["columnName"]) + 1 if field["columnName"] in pk else 0) for field in entity["fields"]}
        if actual != expected:
            raise ValueError(f"table shape mismatch: {table}")
        foreign_expected = {
            (foreign["table"], column, referenced, foreign["onUpdate"], foreign["onDelete"])
            for foreign in entity.get("foreignKeys", [])
            for column, referenced in zip(foreign["columns"], foreign["referencedColumns"])
        }
        foreign_actual = {
            (row[2], row[3], row[4], row[5], row[6])
            for row in db.execute(f'PRAGMA foreign_key_list("{table}")')
        }
        if foreign_actual != foreign_expected:
            raise ValueError(f"foreign key shape mismatch: {table}")
        for index in entity.get("indices", []):
            names = {row[1]: bool(row[2]) for row in db.execute(f'PRAGMA index_list("{table}")')}
            if names.get(index["name"]) != index["unique"]:
                raise ValueError(f"index mismatch: {index['name']}")
            columns = [row[2] for row in db.execute(f'PRAGMA index_info("{index["name"]}")')]
            if columns != index["columnNames"]:
                raise ValueError(f"index columns mismatch: {index['name']}")
    check(db)


def retire_legacy_migration_state_triggers(db: sqlite3.Connection) -> list[str]:
    """Remove only generated triggers for a now-excluded technical state table."""
    removed = []
    for name, sql in db.execute(
        "SELECT name, sql FROM sqlite_master WHERE type='trigger' AND tbl_name=?",
        (LEGACY_MIGRATION_STATE_TABLE,),
    ).fetchall():
        matches = [
            marker for prefix, marker in LEGACY_TRIGGER_PREFIXES.items()
            for op in ("INSERT", "UPDATE", "DELETE")
            if name == f"{prefix}_{LEGACY_MIGRATION_STATE_TABLE}_{op}"
            and f"AFTER {op} ON `{LEGACY_MIGRATION_STATE_TABLE}`" in sql
        ]
        if len(matches) != 1 or matches[0] not in sql:
            raise ValueError(f"unexpected migration-state trigger: {name}")
        db.execute(f'DROP TRIGGER "{name}"')
        removed.append(name)
    return sorted(removed)


def migrate(source: Path, output: Path) -> dict:
    if not source.is_file() or output.exists() or source.resolve() == output.resolve():
        raise ValueError("source missing, output exists, or paths identical")
    src = sqlite3.connect(f"file:{source.resolve()}?mode=ro", uri=True)
    temp = None
    try:
        version = src.execute("PRAGMA user_version").fetchone()[0]
        if version not in (21, 22, 23):
            raise ValueError(f"unsupported source schema: {version}")
        validate(src, version)
        before = snapshot(src)
        output.parent.mkdir(parents=True, exist_ok=True)
        fd, name = tempfile.mkstemp(prefix=".personalhub-v23-", suffix=".db", dir=output.parent)
        os.close(fd)
        temp = Path(name)
        dst = sqlite3.connect(temp)
        try:
            src.backup(dst)
            dst.execute("PRAGMA journal_mode=DELETE")
            for next_version in range(version + 1, 24):
                for entity in schema(next_version)["entities"]:
                    table = entity["tableName"]
                    if table in ADDED[next_version]:
                        dst.execute(entity["createSql"].replace("${TABLE_NAME}", table))
                        for index in entity.get("indices", []):
                            dst.execute(index["createSql"].replace("${TABLE_NAME}", table))
                dst.execute("UPDATE room_master_table SET identity_hash=? WHERE id=42", (schema(next_version)["identityHash"],))
                dst.execute(f"PRAGMA user_version={next_version}")
            retired_triggers = retire_legacy_migration_state_triggers(dst)
            dst.commit()
            validate(dst, 23)
            if dst.execute(
                "SELECT 1 FROM sqlite_master WHERE type='trigger' AND tbl_name=?",
                (LEGACY_MIGRATION_STATE_TABLE,),
            ).fetchone():
                raise ValueError("migration-state trigger remains")
            after = snapshot(dst)
            if {table: after.get(table) for table in before} != before:
                raise ValueError("existing table data changed")
            if any(after[table][0] for table in after.keys() - before.keys()):
                raise ValueError("new table unexpectedly populated")
        finally:
            dst.close()
        os.replace(temp, output)
        temp = None
        report = {"status": "PASS", "source_version": version, "target_version": 23,
                  "source_sha256": digest(source), "output_sha256": digest(output),
                  "retired_legacy_triggers": retired_triggers,
                  "preserved_tables": len(before), "table_counts": {k: v[0] for k, v in after.items()},
                  "quick_check": "ok", "integrity_check": "ok", "foreign_key_check": []}
        Path(str(output) + ".validation.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
        return report
    finally:
        src.close()
        if temp is not None:
            temp.unlink(missing_ok=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(migrate(args.source, args.output), sort_keys=True))
    except Exception as error:
        print(json.dumps({"status": "FAIL", "error": str(error)}, sort_keys=True))
        raise SystemExit(2)
