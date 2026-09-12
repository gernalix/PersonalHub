#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import time

import android_target_preflight as preflight


def _remaining(started_at: float, timeout_s: float, cap_s: float) -> float:
    return max(0.0, min(cap_s, timeout_s - (time.monotonic() - started_at)))


def status(*, timeout_s: float = 30.0) -> dict[str, object]:
    result = dict(preflight.emulator_status(timeout_s=timeout_s))
    # An offline adb row cannot prove which AVD it belongs to. The host process
    # is authoritative for Pixel_8a identity; expose offline rows as evidence,
    # never as a canonical target.
    if result.get("status") == "booting/offline" and result.get("pids"):
        target = result.pop("target", None)
        if target is not None and not result.get("emulators"):
            result["emulators"] = [target]
    return result


def start(*, timeout_s: float = 60.0) -> dict[str, object]:
    before = set(preflight.pixel_8a_pids())
    result = dict(preflight.select_target("emulator", False, timeout_s=timeout_s))
    if result.get("status") == "ok":
        return result

    # The low-level helper may have launched a process before a non-renderer
    # boot failure. Only processes created by this call may be cleaned here;
    # pre-existing Pixel_8a processes are never killed by a failed start.
    after = set(preflight.pixel_8a_pids())
    spawned = sorted(after - before)
    if not spawned:
        return result

    cleaned = preflight.terminate_pids(spawned, timeout_s=5.0, interval_s=0.2)
    residual = sorted(set(preflight.pixel_8a_pids()) - before)
    result["failed_start_cleanup"] = {
        "spawned_pids": spawned,
        "cleaned": bool(cleaned and not residual),
        "residual_pids": residual,
    }
    if residual:
        result["reason"] = "emulator_start_cleanup_failed"
    return result


def wait(*, timeout_s: float = 60.0) -> dict[str, object]:
    return preflight.wait_command(timeout_s=timeout_s)


def stop(*, timeout_s: float = 30.0) -> dict[str, object]:
    return preflight.stop_pixel_8a(timeout_s=min(timeout_s, 30.0))


def smoke(*, timeout_s: float = 90.0) -> dict[str, object]:
    started_at = time.monotonic()
    initial_stop = stop(timeout_s=_remaining(started_at, timeout_s, 30.0))
    if initial_stop.get("status") != "stopped":
        return {"status": "blocked", "reason": "smoke_initial_stop_failed", "detail": initial_stop}
    if pids := preflight.pixel_8a_pids():
        return {"status": "blocked", "reason": "smoke_initial_residual_process", "pids": pids}

    boot_started = time.monotonic()
    start_result = start(timeout_s=_remaining(started_at, timeout_s, 60.0))
    startup_seconds = round(time.monotonic() - boot_started, 3)
    if start_result.get("status") != "ok":
        return {
            "status": "blocked",
            "reason": "smoke_start_failed",
            "startup_seconds": startup_seconds,
            "detail": start_result,
        }

    serial = str(start_result["target"]["serial"])
    boot = preflight.run_adb(["-s", serial, "shell", "getprop", "sys.boot_completed"])
    running_pids = preflight.pixel_8a_pids()
    checks = {
        "boot_completed": boot.returncode == 0 and boot.stdout.strip() == "1",
        "single_pixel_8a_process": len(running_pids) == 1,
    }

    final_stop = stop(timeout_s=_remaining(started_at, timeout_s, 30.0))
    residual = preflight.pixel_8a_pids()
    checks["final_stop_clean"] = final_stop.get("status") == "stopped" and not residual

    if not all(checks.values()):
        return {
            "status": "blocked",
            "reason": "smoke_validation_failed",
            "startup_seconds": startup_seconds,
            "target": start_result["target"],
            "checks": checks,
            "running_pids": running_pids,
            "residual_pids": residual,
            "stop": final_stop,
        }
    return {
        "status": "ok",
        "startup_seconds": startup_seconds,
        "target": start_result["target"],
        "checks": checks,
        "residual_pids": [],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("status", "start", "wait", "stop", "smoke"))
    parser.add_argument("--timeout", type=float, default=None)
    args = parser.parse_args()

    defaults = {"status": 30.0, "start": 60.0, "wait": 60.0, "stop": 30.0, "smoke": 90.0}
    timeout_s = args.timeout if args.timeout is not None else defaults[args.command]
    result = globals()[args.command](timeout_s=timeout_s)
    print(json.dumps(result, sort_keys=True))
    return 0 if result.get("status") in ("ok", "ready", "stopped") else 2


if __name__ == "__main__":
    raise SystemExit(main())
