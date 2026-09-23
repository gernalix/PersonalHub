#!/usr/bin/env python3
"""Deterministic AVD smoke test for PersonalHub public shortcut aliases."""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ANDROID_ATTR = "{http://schemas.android.com/apk/res/android}"
ALIAS_PREFIX = "com.gernalix.personalhub.shortcut."
NAMESPACE_RE = re.compile(r'namespace\s*=\s*"([^"]+)"')


class SmokeError(RuntimeError):
    pass


def run(*args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    proc = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if check and proc.returncode:
        raise SmokeError(f"command failed ({proc.returncode}): {' '.join(args)}\n{proc.stdout.strip()}")
    return proc


def qualify(name: str, namespace: str) -> str:
    if name.startswith("."):
        return namespace + name
    if "." not in name:
        return f"{namespace}.{name}"
    return name


def discover_aliases(repo_root: Path) -> list[tuple[str, str]]:
    aliases: list[tuple[str, str]] = []
    for feature_dir in sorted((repo_root / "feature").glob("*")):
        build_file = feature_dir / "build.gradle.kts"
        manifest = feature_dir / "src/main/AndroidManifest.xml"
        if not build_file.is_file() or not manifest.is_file():
            continue
        namespace_match = NAMESPACE_RE.search(build_file.read_text())
        if not namespace_match:
            continue
        namespace = namespace_match.group(1)
        root = ET.parse(manifest).getroot()
        for element in root.iter("activity-alias"):
            alias = element.attrib.get(ANDROID_ATTR + "name")
            target = element.attrib.get(ANDROID_ATTR + "targetActivity")
            if alias and target and alias.startswith(ALIAS_PREFIX):
                aliases.append((alias, qualify(target, namespace)))

    names = [alias for alias, _ in aliases]
    if len(aliases) != 6 or len(names) != len(set(names)):
        raise SmokeError(f"expected exactly 6 unique public shortcut aliases, found {len(aliases)}: {names}")
    return aliases


def launched_activity(output: str, package: str) -> str | None:
    for line in output.splitlines():
        line = line.strip()
        if line.startswith("Activity: "):
            component = line.removeprefix("Activity: ").strip()
            if "/" in component:
                component_package, activity = component.split("/", 1)
                return qualify(activity, component_package)
            return qualify(component, package)
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--package", default="com.gernalix.personalhub")
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()

    if not args.apk.is_file():
        raise SmokeError(f"APK not found: {args.apk}")

    aliases = discover_aliases(args.repo_root)
    adb = str(Path.home() / "Android/Sdk/platform-tools/adb")
    if not Path(adb).is_file():
        adb = "adb"

    state = run(adb, "-s", args.serial, "get-state").stdout.strip()
    if state != "device":
        raise SmokeError(f"ADB target is not ready: {args.serial} -> {state!r}")

    # Install the exact tested artifact and pre-grant runtime permissions so permission
    # dialogs cannot invalidate the shortcut-routing smoke.
    run(adb, "-s", args.serial, "install", "-r", "-g", str(args.apk))

    for alias, expected_target in aliases:
        run(adb, "-s", args.serial, "shell", "am", "force-stop", args.package)
        proc = run(
            adb,
            "-s",
            args.serial,
            "shell",
            "am",
            "start",
            "-W",
            "-n",
            f"{args.package}/{alias}",
            check=False,
        )
        if proc.returncode or "Error:" in proc.stdout or "Activity class" in proc.stdout:
            raise SmokeError(f"launch failed for {alias}:\n{proc.stdout.strip()}")
        actual = launched_activity(proc.stdout, args.package)
        if actual != expected_target:
            raise SmokeError(
                f"alias target mismatch for {alias}: expected {expected_target}, got {actual or 'unknown'}\n"
                f"{proc.stdout.strip()}"
            )
        print(f"ALIAS_PASS {alias} -> {actual}")

    print("ALIASES_AVD=PASS")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SmokeError as exc:
        print(f"ALIASES_AVD=FAIL: {exc}", file=sys.stderr)
        raise SystemExit(1)
