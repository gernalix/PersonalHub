#!/usr/bin/env python3
"""Validate the real final PersonalHub APK manifest against canonical version.txt."""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE_ID = "com.gernalix.personalhub"


class ApkVersionError(RuntimeError):
    """An APK is not safe to install or distribute as the current PersonalHub version."""


def _apkanalyzer() -> str:
    executable = shutil.which("apkanalyzer")
    if executable:
        return executable
    candidates = [
        Path(sdk) / "cmdline-tools/latest/bin/apkanalyzer"
        for sdk in (os.getenv("ANDROID_HOME"), os.getenv("ANDROID_SDK_ROOT"))
        if sdk
    ]
    candidates.append(Path.home() / "Android/Sdk/cmdline-tools/latest/bin/apkanalyzer")
    for candidate in candidates:
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
    raise ApkVersionError("apkanalyzer unavailable; final APK version validation is mandatory")


def verify_final_apk(
    apk: Path,
    *,
    declared_version: str | None = None,
    repo_root: Path = ROOT,
) -> dict[str, str]:
    """Fail closed before Pixel installation or external APK delivery."""
    try:
        canonical = (repo_root / "version.txt").read_text(encoding="utf-8").strip()
    except OSError as exc:
        raise ApkVersionError("canonical version.txt is unavailable") from exc
    if not canonical.isascii() or not canonical.isdecimal() or int(canonical) < 1:
        raise ApkVersionError(f"invalid canonical version.txt value: {canonical!r}")
    if declared_version is not None and str(declared_version) != canonical:
        raise ApkVersionError(
            f"declared APK version {declared_version!r} disagrees with version.txt {canonical}"
        )
    if not apk.is_file() or apk.suffix.lower() != ".apk":
        raise ApkVersionError(f"final APK file missing or invalid: {apk}")

    analyzer = _apkanalyzer()
    actual: dict[str, str] = {}
    for field in ("application-id", "version-code", "version-name"):
        try:
            result = subprocess.run(
                [analyzer, "manifest", field, str(apk)],
                check=True,
                capture_output=True,
                text=True,
            )
        except (OSError, subprocess.CalledProcessError) as exc:
            raise ApkVersionError(f"could not inspect APK manifest {field}: {apk}") from exc
        actual[field] = result.stdout.strip()

    expected = {
        "application-id": PACKAGE_ID,
        "version-code": canonical,
        "version-name": canonical,
    }
    for field, expected_value in expected.items():
        if actual[field] != expected_value:
            raise ApkVersionError(
                f"APK manifest {field}={actual[field]!r}, expected {expected_value!r}; "
                "installation/publication blocked"
            )
    return actual


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Check final PersonalHub APK version and package identity.")
    parser.add_argument("apk", type=Path)
    parser.add_argument("--version", help="Optional declared delivery version; must match version.txt")
    args = parser.parse_args(argv)
    try:
        values = verify_final_apk(args.apk, declared_version=args.version)
    except ApkVersionError as exc:
        print(f"BLOCKED: {exc}", file=sys.stderr)
        return 2
    print(f"PASS: {values['application-id']} versionCode={values['version-code']} versionName={values['version-name']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
