#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import subprocess


def normalize(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", value.strip().lower()).strip("_")


def parse_devices(raw: str) -> list[dict[str, str]]:
    out = []
    for line in raw.splitlines():
        parts = line.split()
        if len(parts) < 2 or line.startswith("List of devices attached"):
            continue
        attrs = {}
        for item in parts[2:]:
            if ":" in item:
                key, value = item.split(":", 1)
                if key != "transport_id":
                    attrs[key] = value
        model = attrs.get("model", "")
        serial = parts[0]
        norm = normalize(model)
        kind = "emulator" if serial.startswith("emulator-") else (
            "pixel" if "pixel_8a" in norm else (
                "tcl" if "6102h" in norm else "physical_other"
            )
        )
        out.append({"serial": serial, "state": parts[1], "model": model, "kind": kind})
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", choices=("pixel", "tcl", "emulator", "any"), default="any")
    parser.add_argument("--allow-emulator-fallback", action="store_true")
    args = parser.parse_args()
    result = subprocess.run(["adb", "devices", "-l"], text=True, capture_output=True, check=False, timeout=30)
    devices = parse_devices(result.stdout) if result.returncode == 0 else []
    live = [row for row in devices if row["state"] == "device"]
    order = [args.target] if args.target != "any" else ["pixel", "tcl", "emulator"]
    if args.target == "pixel" and args.allow_emulator_fallback:
        order.append("emulator")
    chosen = next((row for kind in order for row in live if row["kind"] == kind), None)
    if chosen:
        print(json.dumps({"status": "ok", "target": chosen}, sort_keys=True))
        return 0
    reason = "pixel_physical_required_but_absent" if args.target == "pixel" and not args.allow_emulator_fallback else "target_unavailable"
    print(json.dumps({"status": "blocked", "reason": reason}, sort_keys=True))
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
