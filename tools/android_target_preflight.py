#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import subprocess
import time


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


def run_adb(args: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["adb", *args], text=True, capture_output=True, check=False, timeout=30)


def list_devices(runner=run_adb, *, allow_recovery: bool = True) -> tuple[list[dict[str, str]], bool]:
    result = runner(["devices", "-l"])
    recovered = False
    if result.returncode != 0 and allow_recovery:
        runner(["start-server"])
        result = runner(["devices", "-l"])
        recovered = True
    return (parse_devices(result.stdout) if result.returncode == 0 else [], recovered)


def live_devices(runner=run_adb) -> tuple[list[dict[str, str]], bool]:
    devices, recovered = list_devices(runner)
    return [row for row in devices if row["state"] == "device"], recovered


def resolve_avd_name(serial: str, runner=run_adb) -> str:
    result = runner(["-s", serial, "emu", "avd", "name"])
    if result.returncode != 0:
        return ""
    return next((line.strip() for line in result.stdout.splitlines() if line.strip() and line.strip() != "OK"), "")


def choose_target(live: list[dict[str, str]], target: str, allow_emulator_fallback: bool) -> dict[str, str] | None:
    order = [target] if target != "any" else ["pixel", "tcl", "emulator"]
    if target == "pixel" and allow_emulator_fallback:
        order.append("emulator")
    return next((row for kind in order for row in live if row["kind"] == kind), None)


def start_pixel_8a_avd(runner=run_adb) -> None:
    subprocess.Popen(
        ["emulator", "-avd", "Pixel_8a", "-no-snapshot-save"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        start_new_session=True,
    )


def wait_for_pixel_8a_emulator(runner=run_adb, *, timeout_s: float = 120.0, interval_s: float = 2.0) -> dict[str, str] | None:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        live, _ = live_devices(runner)
        for row in live:
            if row["kind"] == "emulator" and resolve_avd_name(row["serial"], runner) == "Pixel_8a":
                return row
        time.sleep(interval_s)
    return None


def select_target(target: str, allow_emulator_fallback: bool, *, runner=run_adb, start_avd=start_pixel_8a_avd) -> dict[str, object]:
    live, recovered = live_devices(runner)
    chosen = choose_target(live, target, allow_emulator_fallback)
    if chosen:
        return {"status": "ok", "target": chosen, "adb_recovered": recovered}

    may_use_emulator = target in ("any", "emulator") or (target == "pixel" and allow_emulator_fallback)
    if may_use_emulator:
        start_avd(runner)
        chosen = wait_for_pixel_8a_emulator(runner)
        if chosen:
            return {"status": "ok", "target": chosen, "adb_recovered": recovered, "avd_started": "Pixel_8a"}

    reason = "pixel_physical_required_but_absent" if target == "pixel" and not allow_emulator_fallback else "target_unavailable"
    return {"status": "blocked", "reason": reason, "adb_recovered": recovered}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", choices=("pixel", "tcl", "emulator", "any"), default="any")
    parser.add_argument("--allow-emulator-fallback", action="store_true")
    args = parser.parse_args()
    result = select_target(args.target, args.allow_emulator_fallback)
    print(json.dumps(result, sort_keys=True))
    return 0 if result["status"] == "ok" else 2


if __name__ == "__main__":
    raise SystemExit(main())
