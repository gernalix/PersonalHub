#!/usr/bin/env python3
"""Copy schema 24 to 25, preserving every existing value and a validated rollback copy.

This tool never accesses Android or writes to its source database/WAL.
"""
from __future__ import annotations

import argparse
from contextlib import closing
import json
import os
from pathlib import Path
import sqlite3
import tempfile

from migrate_personalhub_v21_to_v23 import digest, schema, validate
from migrate_personalhub_v23_to_v24 import projection_digest, validate_identities


def migrate(source: Path, output: Path) -> dict:
    source, output = source.resolve(), output.resolve()
    backup = Path(str(output) + ".backup.db")
    receipt = Path(str(output) + ".validation.json")
    if not source.is_file() or source in (output, backup, receipt) or any(p.exists() for p in (output, backup, receipt)):
        raise ValueError("Source missing, paths overlap or destination already exists")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".ph-countdown-", dir=output.parent) as folder:
        frozen, stage = Path(folder) / "backup.db", Path(folder) / "target.db"
        with closing(sqlite3.connect(f"file:{source}?mode=ro", uri=True)) as src, closing(sqlite3.connect(frozen)) as snapshot:
            src.backup(snapshot)
        with closing(sqlite3.connect(frozen)) as src, closing(sqlite3.connect(stage)) as dst:
            version = src.execute("PRAGMA user_version").fetchone()[0]
            if version not in (24, 25):
                raise ValueError("Source must be schema 24 or 25")
            validate(src, version)
            validate_identities(src)
            src.backup(dst)
            dst.execute("PRAGMA journal_mode=DELETE")
            retired = []
            if version == 24:
                with dst:
                    # Startup validates existing trigger SQL before Room opens. Retire only
                    # the generated triggers whose semantics/column list changed; Room
                    # reinstalls them before the first feature write.
                    for prefix in ("mutation", "hub_git_dirty"):
                        for operation in ("INSERT", "UPDATE", "DELETE"):
                            name = f"{prefix}_since_when_counters_{operation}"
                            if dst.execute("SELECT 1 FROM sqlite_master WHERE type='trigger' AND name=?", (name,)).fetchone():
                                dst.execute(f'DROP TRIGGER "{name}"')
                                retired.append(name)
                    dst.execute("ALTER TABLE since_when_counters ADD COLUMN mode TEXT NOT NULL DEFAULT 'SINCE_WHEN'")
                    dst.execute("UPDATE room_master_table SET identity_hash=? WHERE id=42", (schema(25)["identityHash"],))
                    dst.execute("PRAGMA user_version=25")
            validate(dst, 25)
            validate_identities(dst)
            if dst.execute("SELECT 1 FROM since_when_counters WHERE mode NOT IN ('SINCE_WHEN','COUNTDOWN') LIMIT 1").fetchone():
                raise ValueError("Unknown counter mode")
            for entity in schema(version)["entities"]:
                columns = [field["columnName"] for field in entity["fields"]]
                table = entity["tableName"]
                if projection_digest(src, table, columns) != projection_digest(dst, table, columns):
                    raise ValueError("Existing values changed: " + table)
            report = {
                "status": "PASS", "source_version": version, "target_version": 25,
                "source_snapshot_sha256": digest(frozen), "output_sha256": digest(stage),
                "rollback_backup": str(backup), "quick_check": "ok", "integrity_check": "ok",
                "foreign_key_check": [], "existing_values_preserved": True,
                "retired_counter_triggers": retired, "pixel_cutover": False,
            }
        for path in (frozen, stage):
            os.chmod(path, 0o600)
        os.replace(frozen, backup)
        os.replace(stage, output)
        receipt.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
        os.chmod(receipt, 0o600)
        return report


def rollback(migrated: Path, output: Path) -> None:
    report = json.loads(Path(str(migrated) + ".validation.json").read_text())
    backup = Path(report["rollback_backup"])
    if digest(backup) != report["source_snapshot_sha256"]:
        raise ValueError("Rollback snapshot hash mismatch")
    if output.exists() or output.resolve() in (migrated.resolve(), backup.resolve()):
        raise ValueError("Rollback destination exists or overlaps source")
    with closing(sqlite3.connect(f"file:{backup.resolve()}?mode=ro", uri=True)) as src:
        validate(src, report["source_version"])
        with closing(sqlite3.connect(output)) as dst:
            src.backup(dst)
            validate(dst, report["source_version"])
    os.chmod(output, 0o600)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--rollback", action="store_true")
    args = parser.parse_args()
    if args.rollback:
        rollback(args.source, args.output)
        print(json.dumps({"status": "PASS", "rollback": True}))
    else:
        print(json.dumps(migrate(args.source, args.output), sort_keys=True))
