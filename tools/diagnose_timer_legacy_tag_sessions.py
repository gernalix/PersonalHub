#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import sqlite3
import subprocess
import sys
import tempfile
from pathlib import Path

DEFAULT_PACKAGE = "com.gernalix.personalhub"
DEFAULT_DB = "personalhub.db"


def _adb(serial: str, *args: str, stdout=None) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["adb", "-s", serial, *args],
        stdout=stdout if stdout is not None else subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=stdout is None,
        check=False,
    )


def _copy_run_as_file(serial: str, package: str, remote: str, local: Path, *, required: bool) -> bool:
    with local.open("wb") as out:
        proc = subprocess.run(
            ["adb", "-s", serial, "exec-out", "run-as", package, "cat", remote],
            stdout=out,
            stderr=subprocess.PIPE,
            check=False,
        )
    if proc.returncode == 0 and local.stat().st_size > 0:
        return True
    local.unlink(missing_ok=True)
    if required:
        detail = proc.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"cannot copy {remote}: {detail or f'rc={proc.returncode}'}")
    return False


def _load_rows(db_path: Path) -> tuple[dict, list[dict], set[tuple[int, int]]]:
    uri = f"file:{db_path}?mode=ro"
    conn = sqlite3.connect(uri, uri=True)
    conn.row_factory = sqlite3.Row
    try:
        row = conn.execute("SELECT json FROM snapshot WHERE id = 1").fetchone()
        if row is None:
            raise RuntimeError("snapshot row id=1 is missing")
        snapshot = json.loads(row["json"])

        sessions = [
            dict(r)
            for r in conn.execute(
                "SELECT id, title, start_ms, end_ms "
                "FROM sessions WHERE deleted_at_ms IS NULL AND end_ms IS NOT NULL "
                "ORDER BY start_ms, end_ms, id"
            )
        ]
        edges = {
            (int(r["session_id"]), int(r["tag_id"]))
            for r in conn.execute("SELECT session_id, tag_id FROM session_tags")
        }
        return snapshot, sessions, edges
    finally:
        conn.close()


def diagnose(snapshot: dict, sessions: list[dict], edges: set[tuple[int, int]]) -> dict:
    legacy = [
        r
        for r in snapshot.get("tagSessions", [])
        if int(r.get("endTs", 0)) > int(r.get("startTs", 0))
    ]
    valid_tag_ids = {int(t["id"]) for t in snapshot.get("tags", []) if "id" in t}

    rows = []
    counts = {
        "examined": len(legacy),
        "unique_match_edge_present": 0,
        "unique_match_edge_missing": 0,
        "ambiguous": 0,
        "missing_session": 0,
        "missing_tag": 0,
    }

    for record in legacy:
        tag_id = int(record.get("tagId", 0))
        start_ms = int(record.get("startTs", 0))
        end_ms = int(record.get("endTs", 0))
        wanted_title = str(record.get("sessionTitle", "")).strip()

        exact = [s for s in sessions if int(s["start_ms"]) == start_ms and int(s["end_ms"]) == end_ms]
        selected = None
        match_reason = None
        if len(exact) == 1:
            selected = exact[0]
            match_reason = "unique_interval"
        elif len(exact) > 1 and wanted_title:
            titled = [s for s in exact if str(s.get("title") or "").strip() == wanted_title]
            if len(titled) == 1:
                selected = titled[0]
                match_reason = "unique_interval_and_title"

        if tag_id not in valid_tag_ids:
            status = "missing_tag"
            counts[status] += 1
        elif selected is None and not exact:
            status = "missing_session"
            counts[status] += 1
        elif selected is None:
            status = "ambiguous"
            counts[status] += 1
        else:
            edge = (int(selected["id"]), tag_id)
            status = "unique_match_edge_present" if edge in edges else "unique_match_edge_missing"
            counts[status] += 1

        rows.append(
            {
                "status": status,
                "match_reason": match_reason,
                "legacy": {
                    "session_id": int(record.get("sessionId", 0)),
                    "session_title": wanted_title,
                    "tag_id": tag_id,
                    "start_ms": start_ms,
                    "end_ms": end_ms,
                },
                "exact_interval_candidates": [
                    {
                        "id": int(s["id"]),
                        "title": str(s.get("title") or ""),
                        "start_ms": int(s["start_ms"]),
                        "end_ms": int(s["end_ms"]),
                        "edge_present": (int(s["id"]), tag_id) in edges,
                    }
                    for s in exact
                ],
            }
        )

    return {
        "counts": counts,
        "sessions_total": len(sessions),
        "session_tags_total": len(edges),
        "records": rows,
    }


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Read-only diagnosis of legacy Timer tag/session repair on an Android target."
    )
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--db", default=DEFAULT_DB)
    args = parser.parse_args()

    with tempfile.TemporaryDirectory(prefix="ph_timer_repair_") as tmp:
        root = Path(tmp)
        db_path = root / args.db
        try:
            _copy_run_as_file(
                args.serial,
                args.package,
                f"databases/{args.db}",
                db_path,
                required=True,
            )
            _copy_run_as_file(
                args.serial,
                args.package,
                f"databases/{args.db}-wal",
                root / f"{args.db}-wal",
                required=False,
            )
            _copy_run_as_file(
                args.serial,
                args.package,
                f"databases/{args.db}-shm",
                root / f"{args.db}-shm",
                required=False,
            )
            snapshot, sessions, edges = _load_rows(db_path)
            print(json.dumps(diagnose(snapshot, sessions, edges), indent=2, sort_keys=True))
            return 0
        except Exception as exc:
            print(json.dumps({"status": "error", "error": str(exc)}, sort_keys=True), file=sys.stderr)
            return 2


if __name__ == "__main__":
    raise SystemExit(main())
