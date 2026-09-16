#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

import android_target_preflight as preflight


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_METADATA = ROOT / "app/build/outputs/apk/debug/output-metadata.json"


class PixelApkError(RuntimeError):
    pass


def resolve_apk(metadata_path: Path = DEFAULT_METADATA) -> Path:
    metadata_path = metadata_path.expanduser().resolve()
    if not metadata_path.is_file():
        raise PixelApkError(f"apk metadata not found: {metadata_path}")
    try:
        payload = json.loads(metadata_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise PixelApkError(f"invalid apk metadata: {metadata_path}") from exc

    elements = payload.get("elements") if isinstance(payload, dict) else None
    if not isinstance(elements, list):
        raise PixelApkError("apk metadata has no elements list")
    names = [
        str(item.get("outputFile")).strip()
        for item in elements
        if isinstance(item, dict) and str(item.get("outputFile") or "").strip()
    ]
    names = list(dict.fromkeys(names))
    if len(names) != 1:
        raise PixelApkError(f"expected exactly one debug apk, found {len(names)}")

    apk = (metadata_path.parent / names[0]).resolve()
    if not apk.is_file():
        raise PixelApkError(f"apk listed by metadata does not exist: {apk}")
    return apk


def resolve_pixel(*, timeout_s: float = 30.0) -> dict[str, str]:
    result = preflight.select_target("pixel", False, timeout_s=timeout_s)
    target = result.get("target") if isinstance(result, dict) else None
    if result.get("status") != "ok" or not isinstance(target, dict):
        reason = result.get("reason") if isinstance(result, dict) else "invalid_preflight_result"
        raise PixelApkError(f"physical Pixel unavailable: {reason or 'unknown'}")
    if target.get("kind") != "pixel" or not target.get("serial"):
        raise PixelApkError("preflight did not resolve the physical Pixel")
    return {str(key): str(value) for key, value in target.items() if value is not None}


def install(apk: Path, serial: str) -> str:
    result = preflight.run_adb(["-s", serial, "install", "-r", str(apk)])
    if result.returncode != 0:
        detail = (result.stderr or result.stdout or "adb install failed").strip()
        raise PixelApkError(detail[:1000])
    return (result.stdout or "").strip()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Resolve the Gradle debug APK deterministically and optionally install it on the physical Pixel."
    )
    parser.add_argument("command", choices=("resolve", "install"), nargs="?", default="install")
    parser.add_argument("--metadata", type=Path, default=DEFAULT_METADATA)
    parser.add_argument("--timeout", type=float, default=30.0)
    args = parser.parse_args(argv)

    try:
        apk = resolve_apk(args.metadata)
        if args.command == "resolve":
            print(json.dumps({"status": "ok", "apk": str(apk)}, sort_keys=True))
            return 0
        target = resolve_pixel(timeout_s=max(1.0, args.timeout))
        detail = install(apk, target["serial"])
    except PixelApkError as exc:
        print(json.dumps({"status": "blocked", "reason": str(exc)}, sort_keys=True))
        return 2

    print(
        json.dumps(
            {
                "status": "ok",
                "apk": str(apk),
                "serial": target["serial"],
                "model": target.get("model"),
                "install": detail or "Success",
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
