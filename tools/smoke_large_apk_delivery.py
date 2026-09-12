#!/usr/bin/env python3
"""Real end-to-end smoke test for PersonalHub APK delivery above Telegram's cloud limit."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import zipfile
from pathlib import Path

import deliver_personalhub_apk as delivery


SMOKE_RELEASE_TAG = "personalhub-dev-apk-smoke"
SMOKE_RELEASE_TITLE = "PersonalHub large APK delivery smoke test"


def _run(args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, check=True, text=True, capture_output=True)


def validate_real_large_apk(apk_path: Path) -> None:
    if not apk_path.is_file():
        raise FileNotFoundError(str(apk_path))
    if apk_path.stat().st_size <= delivery.TELEGRAM_LIMIT_BYTES:
        raise ValueError(
            f"APK must be larger than {delivery.TELEGRAM_LIMIT_BYTES} bytes; "
            f"got {apk_path.stat().st_size}"
        )
    if not zipfile.is_zipfile(apk_path):
        raise ValueError("file is not a valid ZIP/APK container")
    with zipfile.ZipFile(apk_path) as archive:
        if "AndroidManifest.xml" not in archive.namelist():
            raise ValueError("ZIP does not contain AndroidManifest.xml; refusing synthetic non-APK fixture")


def _release_assets() -> list[str]:
    result = _run([
        "gh",
        "release",
        "view",
        SMOKE_RELEASE_TAG,
        "--repo",
        delivery.REPO,
        "--json",
        "assets",
    ])
    payload = json.loads(result.stdout)
    return [str(asset.get("name", "")) for asset in payload.get("assets", [])]


def run_smoke(apk_path: Path, version: str) -> dict[str, str]:
    validate_real_large_apk(apk_path)

    # Fail before mutating GitHub if the local runtime is not authenticated.
    _run(["gh", "auth", "status", "--hostname", "github.com"])

    commit = delivery._head_sha()
    expected_asset = f"PersonalHub-{version}-{commit[:12]}.apk"
    result = delivery.deliver(
        apk_path,
        version,
        telegram_title="PersonalHub APK >50 MiB smoke test",
        release_tag=SMOKE_RELEASE_TAG,
        release_title=SMOKE_RELEASE_TITLE,
    )
    if not result.startswith("github_release_link"):
        raise RuntimeError(f"large-APK branch was not used: {result}")

    assets = _release_assets()
    if expected_asset not in assets:
        raise RuntimeError(f"uploaded asset not found in {SMOKE_RELEASE_TAG}: {expected_asset}")

    return {
        "status": "PASS",
        "release_tag": SMOKE_RELEASE_TAG,
        "asset": expected_asset,
        "delivery": result,
    }


def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Exercise the real >50 MiB PersonalHub path: authenticated GitHub upload "
            "to an isolated smoke prerelease plus a real Telegram notification."
        )
    )
    parser.add_argument("apk", type=Path, help="Real PersonalHub APK; must be >50 MiB.")
    parser.add_argument("--version", required=True)
    args = parser.parse_args()

    try:
        result = run_smoke(args.apk, args.version)
    except Exception as exc:
        print(f"ERROR: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1

    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
