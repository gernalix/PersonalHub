#!/usr/bin/env python3
"""Local lease for serialized PersonalHub integration, shared QA and release work."""

from __future__ import annotations

import argparse
import json
import os
import socket
import sqlite3
import sys
import time
from pathlib import Path


DEFAULT_LOCK_PATH = Path.home() / ".cache" / "codex" / "personalhub-task.lock"
DEFAULT_ROADMAP_DB = Path.home() / "projects" / "codex-roadmap" / "roadmap.sqlite"
DEFAULT_TTL_SECONDS = 12 * 60 * 60
TERMINAL_PROMPT_STATUSES = {"completed", "failed", "blocked", "superseded", "cancelled"}


def _now() -> int:
    return int(time.time())


def _read_lock(path: Path) -> dict[str, object] | None:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return None
    except json.JSONDecodeError:
        return {"invalid": True, "path": str(path)}


def _is_stale(lock: dict[str, object], ttl_seconds: int) -> bool:
    if lock.get("invalid"):
        return False
    created_at = int(lock.get("created_at", 0) or 0)
    return created_at > 0 and _now() - created_at > ttl_seconds


def _pid_alive(pid: int) -> bool:
    if pid <= 0:
        return False
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def _roadmap_prompt_status(prompt_id: str, roadmap_db: Path) -> str | None:
    if not roadmap_db.is_file():
        return None
    connection: sqlite3.Connection | None = None
    try:
        connection = sqlite3.connect(
            f"file:{roadmap_db}?mode=ro",
            uri=True,
            timeout=0.25,
        )
        row = connection.execute(
            "SELECT status FROM prompts WHERE prompt_id=?",
            (prompt_id,),
        ).fetchone()
    except sqlite3.Error:
        return None
    finally:
        if connection is not None:
            connection.close()
    return str(row[0]) if row else None


def _reclaim_reason(
    lock: dict[str, object],
    ttl_seconds: int,
    roadmap_db: Path,
) -> str | None:
    if lock.get("invalid"):
        return None
    if _is_stale(lock, ttl_seconds):
        return "ttl-expired"

    prompt_id = str(lock.get("prompt_id") or "").strip()
    if prompt_id:
        status = _roadmap_prompt_status(prompt_id, roadmap_db)
        if status in TERMINAL_PROMPT_STATUSES:
            return f"roadmap-terminal:{status}"

    if str(lock.get("host") or "") == socket.gethostname():
        try:
            pid = int(lock.get("pid", 0) or 0)
        except (TypeError, ValueError):
            pid = 0
        if pid > 0 and not _pid_alive(pid):
            return "owner-process-exited"
    return None


def _describe(lock: dict[str, object]) -> str:
    if lock.get("invalid"):
        return f"invalid lock file at {lock.get('path')}"
    return (
        f"PROMPT_ID={lock.get('prompt_id')} "
        f"created_at={lock.get('created_at')} "
        f"host={lock.get('host')} "
        f"pid={lock.get('pid')}"
    )


def _quarantine(path: Path, current: dict[str, object], reason: str) -> None:
    stale_path = path.with_suffix(path.suffix + f".stale.{time.time_ns()}")
    path.rename(stale_path)
    print(f"RECOVERED: {reason}: {_describe(current)} -> {stale_path}")


def acquire(
    path: Path,
    prompt_id: str,
    ttl_seconds: int,
    roadmap_db: Path = DEFAULT_ROADMAP_DB,
) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "prompt_id": prompt_id,
        "created_at": _now(),
        "host": socket.gethostname(),
        "pid": os.getppid(),
    }
    flags = os.O_CREAT | os.O_EXCL | os.O_WRONLY
    try:
        fd = os.open(path, flags, 0o600)
    except FileExistsError:
        current = _read_lock(path)
        if current is not None:
            reason = _reclaim_reason(current, ttl_seconds, roadmap_db)
            if reason is not None:
                _quarantine(path, current, reason)
                return acquire(path, prompt_id, ttl_seconds, roadmap_db)
        print(f"BLOCKED: PersonalHub task lock is held: {_describe(current or {})}", file=sys.stderr)
        return 75
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, sort_keys=True)
        handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())
    print(f"ACQUIRED: {_describe(payload)}")
    return 0


def release(path: Path, prompt_id: str | None) -> int:
    current = _read_lock(path)
    if current is None:
        print("RELEASED: no active PersonalHub task lock")
        return 0
    if prompt_id is not None and str(current.get("prompt_id")) != prompt_id:
        print(f"BLOCKED: lock belongs to another prompt: {_describe(current)}", file=sys.stderr)
        return 75
    path.unlink()
    print(f"RELEASED: {_describe(current)}")
    return 0


def status(
    path: Path,
    ttl_seconds: int,
    roadmap_db: Path = DEFAULT_ROADMAP_DB,
) -> int:
    current = _read_lock(path)
    if current is None:
        print("UNLOCKED")
        return 0
    reason = _reclaim_reason(current, ttl_seconds, roadmap_db)
    state = f"RECLAIMABLE({reason})" if reason else "LOCKED"
    print(f"{state}: {_describe(current)}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Serialize PersonalHub canonical integration, shared QA and release with a local lease.")
    parser.add_argument("command", choices=("acquire", "release", "status"))
    parser.add_argument("--prompt-id", help="Current roadmap PROMPT_ID.")
    parser.add_argument("--lock-path", type=Path, default=DEFAULT_LOCK_PATH)
    parser.add_argument("--roadmap-db", type=Path, default=DEFAULT_ROADMAP_DB)
    parser.add_argument("--ttl-seconds", type=int, default=DEFAULT_TTL_SECONDS)
    args = parser.parse_args()

    if args.command == "acquire":
        if not args.prompt_id:
            parser.error("--prompt-id is required for acquire")
        return acquire(args.lock_path, args.prompt_id, args.ttl_seconds, args.roadmap_db)
    if args.command == "release":
        return release(args.lock_path, args.prompt_id)
    return status(args.lock_path, args.ttl_seconds, args.roadmap_db)


if __name__ == "__main__":
    raise SystemExit(main())
