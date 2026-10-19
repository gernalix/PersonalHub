#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET


def _adb(serial: str, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["adb", "-s", serial, *args],
        # ADB shell forwards inherited stdin; never let it drain UI controller commands.
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=False,
    )


def _dump_xml(serial: str) -> str:
    # uiautomator can report an error with exit 0 and leave an old dump intact.
    # A per-call file prevents stale coordinates from driving the current UI.
    path = f"/sdcard/ph-ui-{uuid.uuid4().hex}.xml"
    try:
        dumped = _adb(serial, "shell", "uiautomator", "dump", path)
        if dumped.returncode != 0 or path not in dumped.stdout:
            raise RuntimeError(
                dumped.stderr.strip() or dumped.stdout.strip() or "uiautomator dump failed"
            )
        pulled = _adb(serial, "exec-out", "cat", path)
        if pulled.returncode != 0 or not pulled.stdout.lstrip().startswith("<?xml"):
            raise RuntimeError(pulled.stderr.strip() or "cannot read fresh UI XML")
        ET.fromstring(pulled.stdout)
        return pulled.stdout
    finally:
        _adb(serial, "shell", "rm", "-f", path)


def _truthy(value: str | None) -> bool:
    return value == "true"


def _short_class(value: str) -> str:
    return value.rsplit(".", 1)[-1] if value else "node"


def _interesting(attrs: dict[str, str]) -> bool:
    return any(
        (
            attrs.get("text", "").strip(),
            attrs.get("content-desc", "").strip(),
            attrs.get("resource-id", "").strip(),
            _truthy(attrs.get("clickable")),
            _truthy(attrs.get("checkable")),
            attrs.get("class", "").endswith("EditText"),
        )
    )


def _search_blob(attrs: dict[str, str]) -> str:
    return " ".join(
        (
            attrs.get("text", ""),
            attrs.get("content-desc", ""),
            attrs.get("resource-id", ""),
            attrs.get("class", ""),
        )
    )


def _format_node(index: int, attrs: dict[str, str]) -> str:
    parts = [f"{index:03d}", _short_class(attrs.get("class", ""))]
    text = attrs.get("text", "").strip()
    desc = attrs.get("content-desc", "").strip()
    resource_id = attrs.get("resource-id", "").strip()
    bounds = attrs.get("bounds", "").strip()
    if text:
        parts.append(f'text="{text}"')
    if desc:
        parts.append(f'desc="{desc}"')
    if resource_id:
        parts.append(f"id={resource_id}")
    flags = []
    for name in ("clickable", "checkable", "checked", "enabled", "selected", "focused"):
        if _truthy(attrs.get(name)):
            flags.append(name)
    if flags:
        parts.append("flags=" + ",".join(flags))
    if bounds:
        parts.append(f"bounds={bounds}")
    return " | ".join(parts)


def summarize(xml_text: str, match: str | None, limit: int) -> list[str]:
    root = ET.fromstring(xml_text)
    pattern = re.compile(match, re.IGNORECASE) if match else None
    lines: list[str] = []
    visible_index = 0
    for node in root.iter("node"):
        attrs = dict(node.attrib)
        if not _interesting(attrs):
            continue
        if pattern and not pattern.search(_search_blob(attrs)):
            continue
        visible_index += 1
        lines.append(_format_node(visible_index, attrs))
        if len(lines) >= limit:
            break
    return lines


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Print a compact semantic summary of the current Android UI tree."
    )
    parser.add_argument("--serial", required=True)
    parser.add_argument(
        "--match",
        help="Optional case-insensitive regex matched against text/description/resource-id/class.",
    )
    parser.add_argument("--limit", type=int, default=120)
    args = parser.parse_args()

    if args.limit < 1:
        parser.error("--limit must be >= 1")

    try:
        xml_text = _dump_xml(args.serial)
        lines = summarize(xml_text, args.match, args.limit)
    except (RuntimeError, ET.ParseError, re.error) as exc:
        print(f"android_ui_summary: {exc}", file=sys.stderr)
        return 2

    if not lines:
        print("NO_MATCHING_UI_NODES")
        return 1 if args.match else 0

    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
