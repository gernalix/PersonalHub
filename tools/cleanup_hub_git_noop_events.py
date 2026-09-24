#!/usr/bin/env python3
"""Create a compacted PersonalHub DB copy without exact no-op Git UPDATE events."""

from __future__ import annotations

import argparse
import json
import sqlite3
from pathlib import Path


def scalar(conn: sqlite3.Connection, sql: str, params: tuple = ()):
    row = conn.execute(sql, params).fetchone()
    return None if row is None else row[0]


def analyze(conn: sqlite3.Connection) -> tuple[int, int, dict[str, int]]:
    total = int(scalar(conn, "SELECT count(*) FROM hub_git_events") or 0)
    noop = int(
        scalar(
            conn,
            "SELECT count(*) FROM hub_git_events "
            "WHERE operation='UPDATE' AND before_payload IS after_payload",
        )
        or 0
    )
    by_table = {
        str(table): int(count)
        for table, count in conn.execute(
            "SELECT table_name,count(*) FROM hub_git_events "
            "WHERE operation='UPDATE' AND before_payload IS after_payload "
            "GROUP BY table_name ORDER BY count(*) DESC"
        )
    }
    return total, noop, by_table


def compact_copy(source: Path, output: Path) -> dict:
    source = source.resolve()
    output = output.resolve()
    if source == output:
        raise ValueError("Refusing in-place cleanup; choose a different --output path.")
    if not source.is_file():
        raise FileNotFoundError(source)
    if output.exists():
        raise FileExistsError(output)
    output.parent.mkdir(parents=True, exist_ok=True)

    src = sqlite3.connect(f"{source.as_uri()}?mode=ro", uri=True)
    try:
        if scalar(src, "PRAGMA quick_check") != "ok":
            raise RuntimeError("Source database failed PRAGMA quick_check")
        if not scalar(
            src,
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='hub_git_events'",
        ):
            raise RuntimeError("Source database has no hub_git_events table")
        total_before, noop_before, by_table = analyze(src)
        source_bytes = source.stat().st_size
        dst = sqlite3.connect(output)
        try:
            src.backup(dst)
            cursor = dst.execute(
                "DELETE FROM hub_git_events "
                "WHERE operation='UPDATE' AND before_payload IS after_payload"
            )
            deleted = cursor.rowcount
            dst.commit()
            dst.execute("VACUUM")
            if scalar(dst, "PRAGMA quick_check") != "ok":
                raise RuntimeError("Compacted database failed PRAGMA quick_check")
            fk_errors = list(dst.execute("PRAGMA foreign_key_check"))
            if fk_errors:
                raise RuntimeError(f"Compacted database has {len(fk_errors)} FK errors")
            total_after, noop_after, _ = analyze(dst)
        finally:
            dst.close()
    except Exception:
        output.unlink(missing_ok=True)
        raise
    finally:
        src.close()
    return {
        "source": str(source),
        "output": str(output),
        "source_bytes": source_bytes,
        "output_bytes": output.stat().st_size,
        "events_before": total_before,
        "events_after": total_after,
        "noop_updates_deleted": deleted,
        "noop_updates_remaining": noop_after,
        "noop_updates_by_table": by_table,
        "quick_check": "ok",
        "foreign_key_errors": 0,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path, help="Source PersonalHub SQLite database")
    parser.add_argument("--output", type=Path, required=True, help="New compacted database path")
    args = parser.parse_args()
    result = compact_copy(args.source, args.output)
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
