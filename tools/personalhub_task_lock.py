#!/usr/bin/env python3
"""Local lease for serialized PersonalHub integration, shared QA and release work."""

from __future__ import annotations

import argparse
import json
import os
import socket
import sys
import time
from pathlib import Path


DEFAULT_LOCK_PATH = Path.home() / ".cache" / "codex" / "personalhub-task.lock"
DEFAULT_TTL_SECONDS = 12 * 60 * 60


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


def acquire(path: Path, prompt_id: str, ttl_seconds: int) -> int:
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
        if current is not None and _is_stale(current, ttl_seconds):
            stale_path = path.with_suffix(path.suffix + f".stale.{_now()}")
            path.rename(stale_path)
            return acquire(path, prompt_id, ttl_seconds)
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


def status(path: Path, ttl_seconds: int) -> int:
    current = _read_lock(path)
    if current is None:
        print("UNLOCKED")
        return 0
    state = "STALE" if _is_stale(current, ttl_seconds) else "LOCKED"
    print(f"{state}: {_describe(current)}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Serialize PersonalHub canonical integration, shared QA and release with a local lease.")
    parser.add_argument("command", choices=("acquire", "release", "status"))
    parser.add_argument("--prompt-id", help="Current roadmap PROMPT_ID.")
    parser.add_argument("--lock-path", type=Path, default=DEFAULT_LOCK_PATH)
    parser.add_argument("--ttl-seconds", type=int, default=DEFAULT_TTL_SECONDS)
    args = parser.parse_args()

    if args.command == "acquire":
        if not args.prompt_id:
            parser.error("--prompt-id is required for acquire")
        return acquire(args.lock_path, args.prompt_id, args.ttl_seconds)
    if args.command == "release":
        return release(args.lock_path, args.prompt_id)
    return status(args.lock_path, args.ttl_seconds)


if __name__ == "__main__":
    raise SystemExit(main())
