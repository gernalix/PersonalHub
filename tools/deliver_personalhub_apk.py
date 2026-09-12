#!/usr/bin/env python3
"""Deliver the final PersonalHub APK without rebuilding for Telegram size limits."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path


TELEGRAM_LIMIT_BYTES = 50 * 1024 * 1024
DEV_RELEASE_TAG = "personalhub-dev-apk"
REPO = "gernalix/PersonalHub"


def _run(args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, check=True, text=True, capture_output=True)


def _repo_visibility() -> tuple[bool, str]:
    result = _run(["gh", "repo", "view", REPO, "--json", "isPrivate,visibility"])
    payload = json.loads(result.stdout)
    return bool(payload["isPrivate"]), str(payload["visibility"])


def _short_head() -> str:
    return _run(["git", "rev-parse", "--short=12", "HEAD"]).stdout.strip()


def _release_url() -> str:
    result = _run(["gh", "release", "view", DEV_RELEASE_TAG, "--repo", REPO, "--json", "url"])
    return str(json.loads(result.stdout)["url"])


def _existing_assets() -> list[str]:
    result = _run(["gh", "release", "view", DEV_RELEASE_TAG, "--repo", REPO, "--json", "assets"])
    payload = json.loads(result.stdout)
    return [asset["name"] for asset in payload.get("assets", []) if asset.get("name", "").endswith(".apk")]


def _ensure_release(version: str, commit: str) -> None:
    title = "PersonalHub development APK"
    notes = f"Current PersonalHub debug APK: version={version}, commit={commit}."
    view = subprocess.run(
        ["gh", "release", "view", DEV_RELEASE_TAG, "--repo", REPO],
        text=True,
        capture_output=True,
    )
    if view.returncode == 0:
        _run(["gh", "release", "edit", DEV_RELEASE_TAG, "--repo", REPO, "--title", title, "--notes", notes, "--prerelease"])
        return
    _run([
        "gh",
        "release",
        "create",
        DEV_RELEASE_TAG,
        "--repo",
        REPO,
        "--title",
        title,
        "--notes",
        notes,
        "--prerelease",
        "--target",
        "HEAD",
    ])


def publish_release_asset(apk_path: Path, version: str) -> tuple[str, bool, str]:
    is_private, visibility = _repo_visibility()
    commit = _short_head()
    _ensure_release(version, commit)
    for asset_name in _existing_assets():
        _run(["gh", "release", "delete-asset", DEV_RELEASE_TAG, asset_name, "--repo", REPO, "-y"])
    asset_name = f"PersonalHub-{version}-{commit}.apk"
    _run(["gh", "release", "upload", DEV_RELEASE_TAG, str(apk_path) + f"#{asset_name}", "--repo", REPO, "--clobber"])
    return _release_url(), is_private, visibility


def deliver(apk_path: Path, version: str, *, telegram_title: str) -> str:
    if not apk_path.is_file():
        raise FileNotFoundError(str(apk_path))
    size = apk_path.stat().st_size
    from telegram_notify import send_file, send_message

    if size <= TELEGRAM_LIMIT_BYTES:
        send_file(apk_path, telegram_title, f"PersonalHub APK {version}")
        return "telegram_file"

    url, is_private, visibility = publish_release_asset(apk_path, version)
    auth_note = " Repository privato: download richiede autenticazione GitHub." if is_private else ""
    send_message(
        telegram_title,
        f"PersonalHub APK {version}: {url}{auth_note}",
    )
    return f"github_release_link visibility={visibility}"


def main() -> int:
    parser = argparse.ArgumentParser(description="Deliver a PersonalHub APK via Telegram or GitHub Release link.")
    parser.add_argument("apk", type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--telegram-title", default="PersonalHub APK")
    args = parser.parse_args()
    try:
        result = deliver(args.apk, args.version, telegram_title=args.telegram_title)
    except Exception as exc:
        print(f"ERROR: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1
    print(f"OK {result}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
