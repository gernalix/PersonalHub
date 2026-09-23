#!/usr/bin/env python3
"""Update the physical Pixel once every non-main PersonalHub branch is in main."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from typing import Any

import android_pixel_apk
import personalhub_task_lock


ROOT = Path(__file__).resolve().parents[1]
STATE_FILE = Path.home() / ".local/state/personalhub/consolidation-deploy.json"
RELEASE_METADATA = ROOT / "app/build/outputs/apk/release/output-metadata.json"
PACKAGE = "com.gernalix.personalhub"
AUTO_OWNER = "personalhub-consolidation-auto"
PROJECT_ID = "49"


class ConsolidationError(RuntimeError):
    pass


def _run(
    args: list[str],
    *,
    cwd: Path = ROOT,
    timeout: int = 300,
    env: dict[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            args,
            cwd=cwd,
            env=env,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
            timeout=timeout,
        )
    except subprocess.TimeoutExpired as exc:
        raise ConsolidationError(f"timeout:{args[0]}") from exc


def _require(proc: subprocess.CompletedProcess[str], label: str) -> str:
    if proc.returncode:
        detail = (proc.stderr or proc.stdout or "").strip().replace("\n", " ")
        raise ConsolidationError(f"{label}:{detail[:500] or proc.returncode}")
    return proc.stdout.strip()


def _git(*args: str, timeout: int = 180) -> str:
    return _require(_run(["git", *args], timeout=timeout), "git_" + args[0].replace("-", "_"))


def _atomic_state(payload: dict[str, Any], path: Path = STATE_FILE) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(payload, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    os.replace(tmp, path)


def _read_state(path: Path = STATE_FILE) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return {"schema_version": 1}
    return payload if isinstance(payload, dict) else {"schema_version": 1}


def _canonical_main() -> str:
    branch = _git("branch", "--show-current")
    if branch != "main":
        raise ConsolidationError(f"canonical_branch_mismatch:{branch or 'detached'}")
    if _git("status", "--porcelain"):
        raise ConsolidationError("canonical_checkout_dirty")
    _git("fetch", "--prune", "origin", timeout=300)
    remote = _git("rev-parse", "refs/remotes/origin/main")
    local = _git("rev-parse", "HEAD")
    if local != remote:
        relation = _run(["git", "merge-base", "--is-ancestor", local, remote])
        if relation.returncode != 0:
            raise ConsolidationError("canonical_checkout_not_fast_forwardable")
        _git("merge", "--ff-only", "refs/remotes/origin/main", timeout=180)
    return _git("rev-parse", "HEAD")


def remote_non_main_branches() -> list[str]:
    raw = _git(
        "for-each-ref",
        "--format=%(refname:short)",
        "refs/remotes/origin",
    )
    return sorted(
        branch
        for branch in raw.splitlines()
        if branch
        and branch not in {"origin/main", "origin/HEAD"}
        and not branch.endswith("/HEAD")
    )


def unmerged_remote_branches(main_ref: str = "refs/remotes/origin/main") -> list[str]:
    pending: list[str] = []
    for branch in remote_non_main_branches():
        proc = _run(["git", "merge-base", "--is-ancestor", branch, main_ref])
        if proc.returncode == 1:
            pending.append(branch.removeprefix("origin/"))
        elif proc.returncode != 0:
            raise ConsolidationError(f"branch_relation_failed:{branch}")
    return pending


def _version() -> str:
    value = (ROOT / "version.txt").read_text(encoding="utf-8").strip()
    if not value.isdigit():
        raise ConsolidationError("invalid_version_txt")
    return value


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _resource_lock(resource: str, command: str) -> int:
    return personalhub_task_lock.main.__wrapped__() if False else _run(
        [
            sys.executable,
            str(ROOT / "tools/personalhub_task_lock.py"),
            command,
            "--prompt-id",
            AUTO_OWNER,
            "--resource",
            resource,
        ],
        timeout=30,
    ).returncode


def _build_release(target_sha: str, state: dict[str, Any]) -> tuple[Path, str]:
    previous = str(state.get("built_sha") or "")
    previous_path = Path(str(state.get("apk_path") or "")) if state.get("apk_path") else None
    previous_hash = str(state.get("apk_sha256") or "")
    if (
        previous == target_sha
        and previous_path is not None
        and previous_path.is_file()
        and previous_hash
        and _sha256(previous_path) == previous_hash
    ):
        return previous_path, previous_hash

    lock = _resource_lock("signing", "acquire")
    if lock != 0:
        raise ConsolidationError("signing_resource_busy")
    try:
        build = _run(
            [
                "./gradlew",
                "--no-daemon",
                "--no-configuration-cache",
                ":app:assembleRelease",
                "--quiet",
                "--console=plain",
            ],
            timeout=1800,
        )
        _require(build, "assemble_release_failed")
    finally:
        _resource_lock("signing", "release")

    apk = android_pixel_apk.resolve_apk(RELEASE_METADATA)
    digest = _sha256(apk)
    state.update(
        {
            "built_sha": target_sha,
            "apk_path": str(apk),
            "apk_sha256": digest,
            "version": _version(),
        }
    )
    _atomic_state(state)
    return apk, digest


def _installed_version(serial: str) -> str:
    try:
        adb = android_pixel_apk.preflight.resolve_tool("adb")
    except FileNotFoundError as exc:
        raise ConsolidationError(str(exc)) from exc
    proc = _run(
        [adb, "-s", serial, "shell", "dumpsys", "package", PACKAGE],
        timeout=60,
    )
    output = _require(proc, "pixel_package_read_failed")
    match = re.search(r"\bversionName=([^\s]+)", output)
    if not match:
        raise ConsolidationError("pixel_version_missing")
    return match.group(1)


def _install_pixel(apk: Path, target_sha: str, state: dict[str, Any]) -> tuple[str, str]:
    if str(state.get("installed_sha") or "") == target_sha:
        return str(state.get("pixel_serial") or ""), str(state.get("installed_version") or "")

    lock = _resource_lock("pixel", "acquire")
    if lock != 0:
        raise ConsolidationError("pixel_resource_busy")
    try:
        target = android_pixel_apk.resolve_pixel(timeout_s=30)
        android_pixel_apk.install(apk, target["serial"], timeout_s=300)
        installed_version = _installed_version(target["serial"])
        expected = _version()
        if installed_version != expected:
            raise ConsolidationError(
                f"pixel_version_mismatch:expected={expected}:actual={installed_version}"
            )
    finally:
        _resource_lock("pixel", "release")

    state.update(
        {
            "installed_sha": target_sha,
            "installed_version": installed_version,
            "pixel_serial": target["serial"],
        }
    )
    _atomic_state(state)
    return target["serial"], installed_version


def _notify(target_sha: str, version: str, branch_count: int, state: dict[str, Any]) -> None:
    if str(state.get("notified_sha") or "") == target_sha:
        return
    env = os.environ.copy()
    env["TELEGRAM_PROJECT_ID"] = PROJECT_ID
    title = "✅ PersonalHub consolidato"
    body = (
        f"Tutti i {branch_count} branch non-main sono inclusi in main.\n"
        f"Pixel aggiornato a PH v{version}.\n"
        f"Main: {target_sha[:12]}"
    )
    sent = _run(
        [sys.executable, "-m", "telegram_notify", title, body],
        timeout=60,
        env=env,
    )
    _require(sent, "telegram_notify_failed")
    state["notified_sha"] = target_sha
    _atomic_state(state)


def run_once() -> dict[str, Any]:
    state = _read_state()
    target_sha = _canonical_main()
    branches = remote_non_main_branches()
    pending = unmerged_remote_branches()
    if pending:
        state.update(
            {
                "observed_main_sha": target_sha,
                "status": "waiting-for-branches",
                "unmerged_branches": pending,
            }
        )
        _atomic_state(state)
        return {
            "status": "waiting",
            "main_sha": target_sha,
            "unmerged_branches": pending,
        }

    if str(state.get("notified_sha") or "") == target_sha:
        return {
            "status": "already-complete",
            "main_sha": target_sha,
            "version": state.get("installed_version") or state.get("version"),
        }

    apk, apk_hash = _build_release(target_sha, state)

    # A merge or new branch may have appeared during the build. Never install a
    # stale consolidation artifact.
    latest_sha = _canonical_main()
    pending_after_build = unmerged_remote_branches()
    if latest_sha != target_sha or pending_after_build:
        state.update(
            {
                "status": "superseded-before-install",
                "observed_main_sha": latest_sha,
                "unmerged_branches": pending_after_build,
            }
        )
        _atomic_state(state)
        return {
            "status": "retry",
            "main_sha": latest_sha,
            "unmerged_branches": pending_after_build,
        }

    serial, version = _install_pixel(apk, target_sha, state)
    _notify(target_sha, version, len(branches), state)
    state.update(
        {
            "status": "complete",
            "main_sha": target_sha,
            "version": version,
            "apk_sha256": apk_hash,
            "pixel_serial": serial,
            "unmerged_branches": [],
        }
    )
    _atomic_state(state)
    return {
        "status": "complete",
        "main_sha": target_sha,
        "version": version,
        "apk_sha256": apk_hash,
        "branch_count": len(branches),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    try:
        result = run_once()
    except (ConsolidationError, android_pixel_apk.PixelApkError, OSError) as exc:
        state = _read_state()
        state.update({"status": "deferred", "reason": str(exc)})
        _atomic_state(state)
        result = {"status": "deferred", "reason": str(exc)}
        code = 0
    else:
        code = 0
    if args.json:
        print(json.dumps(result, sort_keys=True))
    else:
        print(" ".join(f"{key}={value}" for key, value in result.items()))
    return code


if __name__ == "__main__":
    raise SystemExit(main())
