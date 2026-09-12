#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import sys
import time

# Importing the low-level helper must never dirty the repository with __pycache__.
sys.dont_write_bytecode = True

import android_target_preflight as preflight


DEFAULT_TIMEOUTS = {
    "status": 30.0,
    "start": 60.0,
    "wait": 60.0,
    "stop": 30.0,
    "smoke": 90.0,
}
SMOKE_FINAL_STOP_RESERVE_S = 20.0
SMOKE_MIN_TIMEOUT_S = 30.0


def _remaining(started_at: float, timeout_s: float, cap_s: float, *, reserve_s: float = 0.0) -> float:
    return max(0.0, min(cap_s, timeout_s - (time.monotonic() - started_at) - reserve_s))


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

    cleanup_timeout = min(5.0, max(0.1, timeout_s))
    cleaned = preflight.terminate_pids(spawned, timeout_s=cleanup_timeout, interval_s=0.2)
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
    if timeout_s <= 0:
        return {"status": "blocked", "reason": "invalid_timeout", "timeout_s": timeout_s}
    return preflight.stop_pixel_8a(timeout_s=timeout_s)


def _final_smoke_cleanup(started_at: float, timeout_s: float) -> tuple[dict[str, object], list[int]]:
    pids = preflight.pixel_8a_pids()
    if not pids:
        return {"status": "stopped", "already_stopped": True}, []

    cleanup_timeout = _remaining(started_at, timeout_s, SMOKE_FINAL_STOP_RESERVE_S)
    if cleanup_timeout <= 0:
        return {
            "status": "blocked",
            "reason": "smoke_cleanup_budget_exhausted",
            "pids": pids,
        }, pids

    result = stop(timeout_s=cleanup_timeout)
    return result, preflight.pixel_8a_pids()


def smoke(*, timeout_s: float = 90.0) -> dict[str, object]:
    if timeout_s < SMOKE_MIN_TIMEOUT_S:
        return {
            "status": "blocked",
            "reason": "smoke_timeout_too_small",
            "timeout_s": timeout_s,
            "minimum_timeout_s": SMOKE_MIN_TIMEOUT_S,
        }

    started_at = time.monotonic()
    initial_stop_timeout = _remaining(
        started_at,
        timeout_s,
        30.0,
        reserve_s=SMOKE_FINAL_STOP_RESERVE_S,
    )
    initial_stop = stop(timeout_s=initial_stop_timeout)
    if initial_stop.get("status") != "stopped":
        return {"status": "blocked", "reason": "smoke_initial_stop_failed", "detail": initial_stop}
    if pids := preflight.pixel_8a_pids():
        return {"status": "blocked", "reason": "smoke_initial_residual_process", "pids": pids}

    boot_started = time.monotonic()
    start_timeout = _remaining(
        started_at,
        timeout_s,
        60.0,
        reserve_s=SMOKE_FINAL_STOP_RESERVE_S,
    )
    start_result = start(timeout_s=start_timeout)
    startup_seconds = round(time.monotonic() - boot_started, 3)
    if start_result.get("status") != "ok":
        final_stop, residual = _final_smoke_cleanup(started_at, timeout_s)
        return {
            "status": "blocked",
            "reason": "smoke_start_failed",
            "startup_seconds": startup_seconds,
            "detail": start_result,
            "final_stop": final_stop,
            "residual_pids": residual,
        }

    serial = str(start_result["target"]["serial"])
    boot = preflight.run_adb(["-s", serial, "shell", "getprop", "sys.boot_completed"])
    running_pids = preflight.pixel_8a_pids()
    checks = {
        "boot_completed": boot.returncode == 0 and boot.stdout.strip() == "1",
        "single_pixel_8a_process": len(running_pids) == 1,
    }

    final_stop, residual = _final_smoke_cleanup(started_at, timeout_s)
    checks["final_stop_clean"] = final_stop.get("status") == "stopped" and not residual

    payload = {
        "startup_seconds": startup_seconds,
        "target": start_result["target"],
        "checks": checks,
        "running_pids": running_pids,
        "residual_pids": residual,
        "final_stop": final_stop,
    }
    if not all(checks.values()):
        return {"status": "blocked", "reason": "smoke_validation_failed", **payload}
    return {"status": "ok", **payload}


def main() -> int:
    commands = {
        "status": status,
        "start": start,
        "wait": wait,
        "stop": stop,
        "smoke": smoke,
    }
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=tuple(commands))
    parser.add_argument("--timeout", type=float, default=None)
    args = parser.parse_args()

    timeout_s = args.timeout if args.timeout is not None else DEFAULT_TIMEOUTS[args.command]
    if timeout_s <= 0:
        result: dict[str, object] = {
            "status": "blocked",
            "reason": "invalid_timeout",
            "timeout_s": timeout_s,
        }
    else:
        result = commands[args.command](timeout_s=timeout_s)
    print(json.dumps(result, sort_keys=True))
    return 0 if result.get("status") in ("ok", "ready", "stopped") else 2


if __name__ == "__main__":
    raise SystemExit(main())
