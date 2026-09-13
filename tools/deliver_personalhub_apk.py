#!/usr/bin/env python3
"""Deliver the final PersonalHub APK without rebuilding for Telegram size limits."""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


TELEGRAM_LIMIT_BYTES = 50 * 1024 * 1024
DEV_RELEASE_TAG = "personalhub-dev-apk"
DEV_RELEASE_TITLE = "PersonalHub development APK"
REPO = "gernalix/PersonalHub"
REPO_ROOT = Path(__file__).resolve().parents[1]


def _run(args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, check=True, text=True, capture_output=True)


def _repo_visibility() -> tuple[bool, str]:
    result = _run(["gh", "repo", "view", REPO, "--json", "isPrivate,visibility"])
    payload = json.loads(result.stdout)
    return bool(payload["isPrivate"]), str(payload["visibility"])


def _head_sha() -> str:
    return _run(["git", "-C", str(REPO_ROOT), "rev-parse", "HEAD"]).stdout.strip()


def _release_url(release_tag: str = DEV_RELEASE_TAG) -> str:
    result = _run(["gh", "release", "view", release_tag, "--repo", REPO, "--json", "url"])
    return str(json.loads(result.stdout)["url"])


def _existing_assets(release_tag: str = DEV_RELEASE_TAG) -> list[str]:
    result = _run(["gh", "release", "view", release_tag, "--repo", REPO, "--json", "assets"])
    payload = json.loads(result.stdout)
    return [asset["name"] for asset in payload.get("assets", []) if asset.get("name", "").endswith(".apk")]


def _release_exists(release_tag: str = DEV_RELEASE_TAG) -> bool:
    result = subprocess.run(
        ["gh", "release", "view", release_tag, "--repo", REPO],
        text=True,
        capture_output=True,
    )
    return result.returncode == 0


def _release_notes(version: str, commit: str) -> str:
    return f"Current PersonalHub debug APK: version={version}, commit={commit}."


def _ensure_release(
    version: str,
    commit: str,
    *,
    release_tag: str = DEV_RELEASE_TAG,
    release_title: str = DEV_RELEASE_TITLE,
) -> None:
    if _release_exists(release_tag):
        return
    _run([
        "gh",
        "release",
        "create",
        release_tag,
        "--repo",
        REPO,
        "--title",
        release_title,
        "--notes",
        _release_notes(version, commit),
        "--prerelease",
        "--target",
        commit,
    ])


def _update_release_metadata(
    version: str,
    commit: str,
    *,
    release_tag: str = DEV_RELEASE_TAG,
    release_title: str = DEV_RELEASE_TITLE,
) -> None:
    _run([
        "gh",
        "release",
        "edit",
        release_tag,
        "--repo",
        REPO,
        "--title",
        release_title,
        "--notes",
        _release_notes(version, commit),
        "--prerelease",
    ])


def publish_release_asset(
    apk_path: Path,
    version: str,
    *,
    release_tag: str = DEV_RELEASE_TAG,
    release_title: str = DEV_RELEASE_TITLE,
) -> tuple[str, bool, str]:
    is_private, visibility = _repo_visibility()
    commit = _head_sha()
    _ensure_release(
        version,
        commit,
        release_tag=release_tag,
        release_title=release_title,
    )

    # Keep the last known-good APK until the replacement upload succeeds. This
    # makes retries safe and avoids leaving the stable prerelease without an APK.
    previous_assets = _existing_assets(release_tag)
    asset_name = f"PersonalHub-{version}-{commit[:12]}.apk"
    with tempfile.TemporaryDirectory(prefix="personalhub-apk-") as temp_dir:
        staged_apk = Path(temp_dir) / asset_name
        shutil.copyfile(apk_path, staged_apk)
        _run([
            "gh",
            "release",
            "upload",
            release_tag,
            str(staged_apk),
            "--repo",
            REPO,
            "--clobber",
        ])

    for previous_asset in previous_assets:
        if previous_asset == asset_name:
            continue
        _run([
            "gh",
            "release",
            "delete-asset",
            release_tag,
            previous_asset,
            "--repo",
            REPO,
            "-y",
        ])

    # Only advertise the new version/commit after the new asset is known to be
    # present. If upload failed, the old asset and its metadata remain usable.
    _update_release_metadata(
        version,
        commit,
        release_tag=release_tag,
        release_title=release_title,
    )
    return _release_url(release_tag), is_private, visibility


def deliver(
    apk_path: Path,
    version: str,
    *,
    telegram_title: str,
    release_tag: str = DEV_RELEASE_TAG,
    release_title: str = DEV_RELEASE_TITLE,
) -> str:
    if not apk_path.is_file():
        raise FileNotFoundError(str(apk_path))
    size = apk_path.stat().st_size
    from telegram_notify import send_file, send_message

    if size <= TELEGRAM_LIMIT_BYTES:
        send_file(apk_path, telegram_title, f"PersonalHub APK {version}")
        return "telegram_file"

    url, is_private, visibility = publish_release_asset(
        apk_path,
        version,
        release_tag=release_tag,
        release_title=release_title,
    )
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
