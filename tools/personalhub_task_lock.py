#!/usr/bin/env python3
"""Resource-scoped local leases for PersonalHub shared QA/release runtime state."""

from __future__ import annotations

import argparse
import base64
import json
import os
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Any


DEFAULT_LOCK_PATH = Path.home() / ".cache" / "codex" / "personalhub-task.lock"
DEFAULT_LOCK_ROOT = Path.home() / ".cache" / "codex"
RESOURCE_NAMES = ("emulator", "pixel", "release", "signing")
DEFAULT_TTL_SECONDS = 12 * 60 * 60
DEFAULT_ROADMAP_REPOSITORY = "gernalix/codex-roadmap"
DEFAULT_ROADMAP_BRANCH = "main"


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


def _describe(lock: dict[str, object]) -> str:
    if lock.get("invalid"):
        return f"invalid lock file at {lock.get('path')}"
    return (
        f"PROMPT_ID={lock.get('prompt_id')} "
        f"created_at={lock.get('created_at')} "
        f"host={lock.get('host')} "
        f"pid={lock.get('pid')}"
    )


def _gh_json(*args: str) -> dict[str, Any] | None:
    try:
        proc = subprocess.run(
            ["gh", *args],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
    except FileNotFoundError:
        return None
    if proc.returncode:
        return None
    try:
        data = json.loads(proc.stdout)
    except json.JSONDecodeError:
        return None
    return data if isinstance(data, dict) else None


def _remote_prompt_status(
    prompt_id: str,
    repository: str = DEFAULT_ROADMAP_REPOSITORY,
    branch: str = DEFAULT_ROADMAP_BRANCH,
) -> str | None:
    """Best-effort authoritative roadmap status lookup.

    Failure to query the remote is deliberately conservative: callers keep the
    lease instead of guessing that the owner is dead.
    """
    if not prompt_id:
        return None
    payload = _gh_json(
        "api",
        f"repos/{repository}/contents/roadmap.sqlite?ref={branch}",
    )
    if not payload:
        return None
    try:
        raw = base64.b64decode(str(payload["content"]).replace("\n", ""), validate=True)
    except (KeyError, ValueError):
        return None

    try:
        with tempfile.NamedTemporaryFile(suffix=".sqlite") as handle:
            handle.write(raw)
            handle.flush()
            conn = sqlite3.connect(f"file:{handle.name}?mode=ro", uri=True)
            try:
                row = conn.execute(
                    "SELECT status FROM prompts WHERE prompt_id=?",
                    (prompt_id,),
                ).fetchone()
            finally:
                conn.close()
    except sqlite3.DatabaseError:
        return None
    return str(row[0]) if row else None


def _archive_stale_lock(path: Path, reason: str) -> None:
    stale_path = path.with_suffix(path.suffix + f".stale.{_now()}")
    path.rename(stale_path)
    print(f"RECOVERED: {_describe(_read_lock(stale_path) or {})} reason={reason}")


def acquire(
    path: Path,
    prompt_id: str,
    ttl_seconds: int,
    *,
    roadmap_repository: str = DEFAULT_ROADMAP_REPOSITORY,
    roadmap_branch: str = DEFAULT_ROADMAP_BRANCH,
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
        if current is not None and str(current.get("prompt_id") or "") == prompt_id:
            print(f"ALREADY_ACQUIRED: {_describe(current)}")
            return 0
        if current is not None and _is_stale(current, ttl_seconds):
            _archive_stale_lock(path, "ttl-expired")
            return acquire(
                path,
                prompt_id,
                ttl_seconds,
                roadmap_repository=roadmap_repository,
                roadmap_branch=roadmap_branch,
            )
        owner_prompt_id = str((current or {}).get("prompt_id") or "")
        owner_status = _remote_prompt_status(
            owner_prompt_id,
            repository=roadmap_repository,
            branch=roadmap_branch,
        )
        if current is not None and owner_status is not None and owner_status != "running":
            _archive_stale_lock(path, f"owner-roadmap-status:{owner_status}")
            return acquire(
                path,
                prompt_id,
                ttl_seconds,
                roadmap_repository=roadmap_repository,
                roadmap_branch=roadmap_branch,
            )
        suffix = f" roadmap_status={owner_status}" if owner_status else ""
        print(
            f"BLOCKED: PersonalHub task lock is held: {_describe(current or {})}{suffix}",
            file=sys.stderr,
        )
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


def status(path: Path, ttl_seconds: int) -> int:
    current = _read_lock(path)
    if current is None:
        print("UNLOCKED")
        return 0
    state = "STALE" if _is_stale(current, ttl_seconds) else "LOCKED"
    print(f"{state}: {_describe(current)}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Serialize only a genuinely shared PersonalHub runtime resource.")
    parser.add_argument("command", choices=("acquire", "release", "status"))
    parser.add_argument("--prompt-id", help="Current roadmap PROMPT_ID.")
    parser.add_argument("--resource", choices=RESOURCE_NAMES, help="Shared resource to serialize. Omit only for legacy compatibility.")
    parser.add_argument("--lock-path", type=Path)
    parser.add_argument("--ttl-seconds", type=int, default=DEFAULT_TTL_SECONDS)
    parser.add_argument("--roadmap-repository", default=DEFAULT_ROADMAP_REPOSITORY)
    parser.add_argument("--roadmap-branch", default=DEFAULT_ROADMAP_BRANCH)
    args = parser.parse_args()
    lock_path = args.lock_path or (
        DEFAULT_LOCK_ROOT / f"personalhub-{args.resource}.lock"
        if args.resource
        else DEFAULT_LOCK_PATH
    )

    if args.command == "acquire":
        if not args.prompt_id:
            parser.error("--prompt-id is required for acquire")
        return acquire(
            lock_path,
            args.prompt_id,
            args.ttl_seconds,
            roadmap_repository=args.roadmap_repository,
            roadmap_branch=args.roadmap_branch,
        )
    if args.command == "release":
        return release(lock_path, args.prompt_id)
    return status(lock_path, args.ttl_seconds)


if __name__ == "__main__":
    raise SystemExit(main())
