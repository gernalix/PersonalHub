#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
EXPECTED_PACKAGE = "com.gernalix.personalhub"
MIN_PLAY_TARGET_SDK = 36
FORBIDDEN_PERMISSIONS = {
    "android.permission.ACCESS_BACKGROUND_LOCATION",
    "android.permission.USE_FULL_SCREEN_INTENT",
    "android.permission.READ_PHONE_STATE",
    "android.permission.READ_CALL_LOG",
    "android.permission.SYSTEM_ALERT_WINDOW",
}
FORBIDDEN_COMPONENTS = {
    "com.supercontacts.app.CallStateReceiver",
    "com.supercontacts.app.CallOverlayDebugReceiver",
    "com.gernalix.luoghi.capsules.geofence.PlaceGeofenceReceiver",
}


def find_manifest(root: Path) -> Path:
    patterns = (
        "app/build/intermediates/merged_manifest/play/**/AndroidManifest.xml",
        "app/build/intermediates/merged_manifests/play/**/AndroidManifest.xml",
        "app/build/intermediates/packaged_manifests/play/**/AndroidManifest.xml",
    )
    for pattern in patterns:
        matches = sorted(root.glob(pattern))
        if matches:
            return matches[-1]
    raise FileNotFoundError("merged Play AndroidManifest.xml not found; run :app:processPlayMainManifest first")


def validate_manifest(path: Path) -> list[str]:
    root = ET.parse(path).getroot()
    errors: list[str] = []

    package_name = root.attrib.get("package", "")
    if package_name != EXPECTED_PACKAGE:
        errors.append(f"package={package_name!r}, expected {EXPECTED_PACKAGE!r}")

    permissions = {
        node.attrib.get(ANDROID + "name", "")
        for node in root.findall("uses-permission")
    }
    present_forbidden = sorted(FORBIDDEN_PERMISSIONS & permissions)
    if present_forbidden:
        errors.append("forbidden permissions present: " + ", ".join(present_forbidden))

    uses_sdk = root.find("uses-sdk")
    raw_target = uses_sdk.attrib.get(ANDROID + "targetSdkVersion", "") if uses_sdk is not None else ""
    try:
        target = int(raw_target)
    except ValueError:
        target = -1
    if target < MIN_PLAY_TARGET_SDK:
        errors.append(f"targetSdkVersion={raw_target!r}, expected >= {MIN_PLAY_TARGET_SDK}")

    application = root.find("application")
    if application is None:
        errors.append("application element missing")
    else:
        components = {
            node.attrib.get(ANDROID + "name", "")
            for tag in ("activity", "activity-alias", "receiver", "service", "provider")
            for node in application.findall(tag)
        }
        present_components = sorted(FORBIDDEN_COMPONENTS & components)
        if present_components:
            errors.append("forbidden Play components present: " + ", ".join(present_components))

    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Validate the merged PersonalHub Play manifest.")
    parser.add_argument("manifest", nargs="?", type=Path)
    args = parser.parse_args(argv)

    try:
        manifest = args.manifest or find_manifest(Path.cwd())
        errors = validate_manifest(manifest)
    except (OSError, ET.ParseError) as exc:
        print(f"PLAY_MANIFEST=FAIL {exc}", file=sys.stderr)
        return 2

    if errors:
        for error in errors:
            print(f"PLAY_MANIFEST=FAIL {error}", file=sys.stderr)
        return 1

    print(f"PLAY_MANIFEST=PASS path={manifest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
