#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import re
import signal
import shutil
import subprocess
import tempfile
import time
from contextlib import contextmanager
from contextvars import ContextVar
from pathlib import Path


_OPERATION_DEADLINE: ContextVar[float | None] = ContextVar("android_emulator_operation_deadline", default=None)
_RECOVERY_STATE: ContextVar[dict[str, bool] | None] = ContextVar("android_emulator_recovery_state", default=None)


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


def sdk_roots() -> list[Path]:
    roots = []
    for key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        if os.environ.get(key):
            roots.append(Path(os.environ[key]).expanduser())
    roots.extend((Path.home() / "Android" / "Sdk", Path.home() / "Android" / "sdk"))
    return list(dict.fromkeys(roots))


def resolve_tool(name: str) -> str:
    if found := shutil.which(name):
        return found
    relative = Path("platform-tools/adb") if name == "adb" else Path("emulator/emulator")
    for root in sdk_roots():
        candidate = root / relative
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
    raise FileNotFoundError(f"{name}_not_found")


@contextmanager
def operation_scope(timeout_s: float):
    current_deadline = _OPERATION_DEADLINE.get()
    if current_deadline is not None:
        yield
        return
    deadline_token = _OPERATION_DEADLINE.set(time.monotonic() + max(0.0, timeout_s))
    recovery_token = _RECOVERY_STATE.set({"server_attempted": False, "offline_attempted": False})
    try:
        yield
    finally:
        _RECOVERY_STATE.reset(recovery_token)
        _OPERATION_DEADLINE.reset(deadline_token)


def operation_expired() -> bool:
    deadline = _OPERATION_DEADLINE.get()
    return deadline is not None and time.monotonic() >= deadline


def remaining_timeout(cap_s: float) -> float:
    deadline = _OPERATION_DEADLINE.get()
    if deadline is None:
        return cap_s
    return max(0.0, min(cap_s, deadline - time.monotonic()))


def _timeout_result(args: list[str], reason: str = "operation_timeout") -> subprocess.CompletedProcess[str]:
    return subprocess.CompletedProcess(args, 124, "", reason)


def run_adb(args: list[str]) -> subprocess.CompletedProcess[str]:
    timeout_s = remaining_timeout(30.0)
    if timeout_s <= 0.0:
        return _timeout_result(args)
    try:
        adb = resolve_tool("adb")
    except FileNotFoundError as exc:
        return subprocess.CompletedProcess(args, 127, "", str(exc))
    try:
        return subprocess.run(
            [adb, *args],
            text=True,
            capture_output=True,
            check=False,
            timeout=timeout_s,
        )
    except subprocess.TimeoutExpired as exc:
        reason = "operation_timeout" if operation_expired() else f"adb_timeout: {' '.join(args)}"
        return subprocess.CompletedProcess(args, 124, exc.stdout or "", reason)


def list_devices(runner=run_adb, *, allow_recovery: bool = True) -> tuple[list[dict[str, str]], bool]:
    state = _RECOVERY_STATE.get()
    if state is None:
        state = {"server_attempted": False, "offline_attempted": False}

    result = runner(["devices", "-l"])
    recovered = False

    if (
        result.returncode != 0
        and allow_recovery
        and not state["server_attempted"]
        and not operation_expired()
    ):
        state["server_attempted"] = True
        recovered = True
        runner(["start-server"])
        if not operation_expired():
            result = runner(["devices", "-l"])

    devices = parse_devices(result.stdout) if result.returncode == 0 else []
    if (
        allow_recovery
        and any(row["state"] == "offline" for row in devices)
        and not state["offline_attempted"]
        and not operation_expired()
    ):
        state["offline_attempted"] = True
        recovered = True
        runner(["reconnect", "offline"])
        if not operation_expired():
            result = runner(["devices", "-l"])
            if result.returncode == 0:
                devices = parse_devices(result.stdout)

    return devices, recovered


def live_devices(runner=run_adb) -> tuple[list[dict[str, str]], bool]:
    devices, recovered = list_devices(runner)
    return [row for row in devices if row["state"] == "device"], recovered


def resolve_avd_name(serial: str, runner=run_adb) -> str:
    result = runner(["-s", serial, "emu", "avd", "name"])
    if result.returncode != 0:
        return ""
    return next((line.strip() for line in result.stdout.splitlines() if line.strip() and line.strip() != "OK"), "")


def boot_completed(serial: str, runner=run_adb) -> bool:
    result = runner(["-s", serial, "shell", "getprop", "sys.boot_completed"])
    return result.returncode == 0 and result.stdout.strip() == "1"


def choose_target(live: list[dict[str, str]], target: str, allow_emulator_fallback: bool) -> dict[str, str] | None:
    order = [target] if target != "any" else ["emulator", "tcl", "pixel"]
    if target == "pixel" and allow_emulator_fallback:
        order.append("emulator")
    return next((row for kind in order for row in live if row["kind"] == kind), None)


def list_avds() -> tuple[str, list[str]]:
    emulator = resolve_tool("emulator")
    timeout_s = remaining_timeout(30.0)
    if timeout_s <= 0.0:
        raise RuntimeError("operation_timeout")
    try:
        result = subprocess.run(
            [emulator, "-list-avds"],
            text=True,
            capture_output=True,
            check=False,
            timeout=timeout_s,
        )
    except subprocess.TimeoutExpired as exc:
        reason = "operation_timeout" if operation_expired() else "emulator_list_avds_timeout"
        raise RuntimeError(reason) from exc
    avds = [line.strip() for line in result.stdout.splitlines() if line.strip()] if result.returncode == 0 else []
    return emulator, avds


def pixel_8a_pids() -> list[int]:
    pids = []
    proc = Path("/proc")
    for item in proc.iterdir():
        if not item.name.isdigit():
            continue
        try:
            cmdline = (item / "cmdline").read_bytes()
        except OSError:
            continue
        parts = [part for part in cmdline.split(b"\0") if part]
        if not parts:
            continue
        executable = Path(parts[0].decode("utf-8", errors="ignore")).name
        if not ("emulator" in executable or executable.startswith("qemu-system")):
            continue
        if any(part == b"Pixel_8a" or part == b"@Pixel_8a" for part in parts):
            pids.append(int(item.name))
    return pids


def _local_deadline(timeout_s: float) -> float:
    local = time.monotonic() + max(0.0, timeout_s)
    global_deadline = _OPERATION_DEADLINE.get()
    return min(local, global_deadline) if global_deadline is not None else local


def terminate_pids(pids: list[int], *, timeout_s: float = 15.0, interval_s: float = 0.2) -> bool:
    live = sorted(set(pids))
    for pid in live:
        try:
            os.kill(pid, signal.SIGTERM)
        except ProcessLookupError:
            pass

    deadline = _local_deadline(timeout_s)
    while time.monotonic() < deadline:
        live = [pid for pid in live if Path(f"/proc/{pid}").exists()]
        if not live:
            return True
        time.sleep(min(interval_s, max(0.0, deadline - time.monotonic())))

    for pid in live:
        try:
            os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            pass

    deadline = _local_deadline(5.0)
    while time.monotonic() < deadline:
        live = [pid for pid in live if Path(f"/proc/{pid}").exists()]
        if not live:
            return True
        time.sleep(min(interval_s, max(0.0, deadline - time.monotonic())))
    return False


def terminate_process(process, *, timeout_s: float = 15.0) -> bool:
    if process is None or process.poll() is not None:
        return True
    process.terminate()
    wait_s = remaining_timeout(timeout_s)
    if wait_s <= 0.0:
        return False
    try:
        process.wait(timeout=wait_s)
    except subprocess.TimeoutExpired:
        process.kill()
        wait_s = remaining_timeout(5.0)
        if wait_s <= 0.0:
            return False
        try:
            process.wait(timeout=wait_s)
        except subprocess.TimeoutExpired:
            return False
    return process.poll() is not None


def start_pixel_8a_avd(extra_args: list[str] | None = None) -> dict[str, object]:
    emulator, avds = list_avds()
    if "Pixel_8a" not in avds:
        raise RuntimeError("Pixel_8a_avd_not_found")
    if operation_expired():
        raise RuntimeError("operation_timeout")
    suffix = "-software" if extra_args else ""
    log_path = os.path.join(tempfile.gettempdir(), f"personalhub-pixel_8a-emulator{suffix}.log")
    log = open(log_path, "w", encoding="utf-8")
    proc = subprocess.Popen(
        [
            emulator,
            "-avd", "Pixel_8a",
            "-qt-hide-window",
            "-no-boot-anim",
            "-no-snapshot-load",
            "-no-snapshot-save",
            *(extra_args or []),
        ],
        stdout=log,
        stderr=subprocess.STDOUT,
        text=True,
        start_new_session=True,
    )
    log.close()
    return {"process": proc, "log_path": log_path}


def renderer_failure(log_path: str) -> bool:
    try:
        text = Path(log_path).read_text(encoding="utf-8", errors="replace").lower()
    except OSError:
        return False
    failure_patterns = (
        r"\b(fatal|error|failed|failure|crash|abort|cannot|could not|unable)\b.{0,80}\b(renderer|vulkan|gpu|opengl|egl|gles)\b",
        r"\b(renderer|vulkan|gpu|opengl|egl|gles)\b.{0,80}\b(fatal|error|failed|failure|crash|abort|cannot|could not|unable)\b",
    )
    return any(re.search(pattern, text, re.DOTALL) for pattern in failure_patterns)


def wait_for_pixel_8a_emulator(
    runner=run_adb,
    *,
    process=None,
    timeout_s: float = 180.0,
    interval_s: float = 2.0,
) -> dict[str, str] | None:
    with operation_scope(timeout_s):
        deadline = _local_deadline(timeout_s)
        while time.monotonic() < deadline:
            if process is not None and process.poll() is not None:
                return None
            live, _ = live_devices(runner)
            for row in live:
                if (
                    row["kind"] == "emulator"
                    and resolve_avd_name(row["serial"], runner) == "Pixel_8a"
                    and boot_completed(row["serial"], runner)
                ):
                    return row
            if operation_expired():
                return None
            sleep_s = min(interval_s, max(0.0, deadline - time.monotonic()))
            if sleep_s > 0.0:
                time.sleep(sleep_s)
        return None


def _wait_timeout(cap_s: float) -> float:
    return remaining_timeout(cap_s)


def select_target(
    target: str,
    allow_emulator_fallback: bool,
    *,
    runner=run_adb,
    start_avd=start_pixel_8a_avd,
    waiter=wait_for_pixel_8a_emulator,
    renderer_check=renderer_failure,
    process_finder=pixel_8a_pids,
    process_terminator=terminate_pids,
    timeout_s: float = 180.0,
) -> dict[str, object]:
    with operation_scope(timeout_s):
        devices, recovered = list_devices(runner)
        if operation_expired():
            return {"status": "blocked", "reason": "operation_timeout", "adb_recovered": recovered}

        live = [row for row in devices if row["state"] == "device"]
        chosen = choose_target(live, target, allow_emulator_fallback)
        if chosen and chosen["kind"] == "emulator" and resolve_avd_name(chosen["serial"], runner) != "Pixel_8a":
            chosen = None

        if chosen:
            if chosen["kind"] != "emulator" or boot_completed(chosen["serial"], runner):
                return {"status": "ok", "target": chosen, "adb_recovered": recovered}
            if resolve_avd_name(chosen["serial"], runner) == "Pixel_8a":
                wait_s = _wait_timeout(60.0)
                if wait_s <= 0.0:
                    return {"status": "blocked", "reason": "operation_timeout", "adb_recovered": recovered}
                ready = waiter(runner, timeout_s=wait_s)
                if ready:
                    return {"status": "ok", "target": ready, "adb_recovered": recovered}
                reason = "operation_timeout" if operation_expired() else "Pixel_8a_booting_or_offline"
                return {
                    "status": "blocked",
                    "reason": reason,
                    "adb_recovered": recovered,
                    "target": chosen,
                }

        may_use_emulator = target in ("any", "emulator") or (target == "pixel" and allow_emulator_fallback)
        if may_use_emulator:
            existing_pixel_processes = process_finder()
            offline_emulators = [
                row for row in devices
                if row["kind"] == "emulator" and row["state"] == "offline"
            ]

            # An offline row alone is not enough to identify Pixel_8a. Only a real
            # Pixel_8a host process may block a new canonical launch.
            if existing_pixel_processes:
                wait_s = _wait_timeout(60.0)
                if wait_s <= 0.0:
                    return {
                        "status": "blocked",
                        "reason": "operation_timeout",
                        "adb_recovered": recovered,
                        "pids": existing_pixel_processes,
                    }
                ready = waiter(runner, timeout_s=wait_s)
                if ready:
                    return {"status": "ok", "target": ready, "adb_recovered": recovered}
                reason = "operation_timeout" if operation_expired() else "Pixel_8a_booting_or_offline"
                return {
                    "status": "blocked",
                    "reason": reason,
                    "adb_recovered": recovered,
                    "emulators": offline_emulators,
                    "pids": existing_pixel_processes,
                }

            if operation_expired():
                return {"status": "blocked", "reason": "operation_timeout", "adb_recovered": recovered}

            try:
                started = start_avd()
            except (FileNotFoundError, RuntimeError) as exc:
                return {"status": "blocked", "reason": str(exc), "adb_recovered": recovered}

            wait_s = _wait_timeout(180.0)
            chosen = waiter(runner, process=started.get("process"), timeout_s=wait_s) if wait_s > 0.0 else None
            used_renderer_fallback = False

            if (
                not chosen
                and not operation_expired()
                and started.get("log_path")
                and renderer_check(str(started["log_path"]))
            ):
                if not terminate_process(started.get("process")):
                    return {
                        "status": "blocked",
                        "reason": "renderer_retry_previous_process_still_live",
                        "adb_recovered": recovered,
                        "emulator_log": started["log_path"],
                    }

                residual_pids = process_finder()
                if residual_pids:
                    cleanup_s = remaining_timeout(15.0)
                    cleaned = cleanup_s > 0.0 and process_terminator(
                        residual_pids,
                        timeout_s=cleanup_s,
                        interval_s=0.2,
                    )
                    residual_pids = process_finder()
                    if not cleaned or residual_pids:
                        return {
                            "status": "blocked",
                            "reason": "renderer_retry_residual_pixel_8a_process",
                            "adb_recovered": recovered,
                            "pids": residual_pids,
                            "emulator_log": started["log_path"],
                        }

                if operation_expired():
                    return {
                        "status": "blocked",
                        "reason": "operation_timeout",
                        "adb_recovered": recovered,
                        "emulator_log": started["log_path"],
                    }

                try:
                    started = start_avd(["-gpu", "swiftshader_indirect"])
                except (FileNotFoundError, RuntimeError) as exc:
                    return {"status": "blocked", "reason": str(exc), "adb_recovered": recovered}
                used_renderer_fallback = True
                wait_s = _wait_timeout(180.0)
                chosen = waiter(runner, process=started.get("process"), timeout_s=wait_s) if wait_s > 0.0 else None

            if chosen:
                result: dict[str, object] = {
                    "status": "ok",
                    "target": chosen,
                    "adb_recovered": recovered,
                    "avd_started": "Pixel_8a",
                }
                if used_renderer_fallback:
                    result["renderer_fallback"] = "swiftshader_indirect"
                return result

            reason = "operation_timeout" if operation_expired() else "emulator_start_failed"
            result = {"status": "blocked", "reason": reason, "adb_recovered": recovered}
            if started.get("log_path"):
                result["emulator_log"] = started["log_path"]
            return result

        reason = "pixel_physical_required_but_absent" if target == "pixel" and not allow_emulator_fallback else "target_unavailable"
        return {"status": "blocked", "reason": reason, "adb_recovered": recovered}


def emulator_status(runner=run_adb, *, timeout_s: float = 180.0) -> dict[str, object]:
    with operation_scope(timeout_s):
        try:
            emulator, avds = list_avds()
            adb = resolve_tool("adb")
        except (FileNotFoundError, RuntimeError) as exc:
            return {"status": "blocked", "reason": str(exc)}

        devices, recovered = list_devices(runner)
        if operation_expired():
            return {"status": "blocked", "reason": "operation_timeout", "adb_recovered": recovered}

        emulator_rows = [row for row in devices if row["kind"] == "emulator"]
        for row in emulator_rows:
            if row["state"] != "device":
                continue
            if resolve_avd_name(row["serial"], runner) != "Pixel_8a":
                continue
            ready = boot_completed(row["serial"], runner)
            return {
                "status": "ready" if ready else "booting",
                "target": row,
                "readiness": {"adb_state": row["state"], "sys.boot_completed": "1" if ready else "0"},
                "adb": adb,
                "emulator": emulator,
                "adb_recovered": recovered,
            }

        pids = pixel_8a_pids()
        if pids:
            offline_rows = [row for row in emulator_rows if row["state"] == "offline"]
            result: dict[str, object] = {
                "status": "booting/offline",
                "reason": "Pixel_8a_process_running_without_ready_adb",
                "pids": pids,
                "adb": adb,
                "emulator": emulator,
                "adb_recovered": recovered,
            }
            if len(offline_rows) == 1:
                result["target"] = offline_rows[0]
                result["readiness"] = {
                    "adb_state": "offline",
                    "sys.boot_completed": "unknown",
                }
            elif offline_rows:
                result["emulators"] = offline_rows
            return result

        return {
            "status": "stopped" if "Pixel_8a" in avds else "blocked",
            "reason": None if "Pixel_8a" in avds else "Pixel_8a_avd_not_found",
            "adb": adb,
            "emulator": emulator,
            "emulators": emulator_rows,
            "adb_recovered": recovered,
        }


def wait_command(runner=run_adb, *, timeout_s: float = 180.0) -> dict[str, object]:
    with operation_scope(timeout_s):
        ready = wait_for_pixel_8a_emulator(runner, timeout_s=timeout_s)
        if ready:
            return {
                "status": "ready",
                "target": ready,
                "readiness": {"adb_state": "device", "sys.boot_completed": "1"},
            }
        reason = "operation_timeout" if operation_expired() else "Pixel_8a_readiness_timeout"
        return {"status": "blocked", "reason": reason}


def stop_pixel_8a(runner=run_adb, *, timeout_s: float = 30.0, interval_s: float = 1.0) -> dict[str, object]:
    with operation_scope(timeout_s):
        devices, recovered = list_devices(runner)
        if operation_expired():
            return {"status": "blocked", "reason": "operation_timeout", "adb_recovered": recovered}

        targets = [
            row for row in devices
            if row["kind"] == "emulator"
            and row["state"] == "device"
            and resolve_avd_name(row["serial"], runner) == "Pixel_8a"
        ]
        pids = pixel_8a_pids()

        if not targets and pids:
            cleanup_s = remaining_timeout(timeout_s)
            if cleanup_s > 0.0 and terminate_pids(pids, timeout_s=cleanup_s, interval_s=interval_s):
                return {"status": "stopped", "pids": pids, "adb_recovered": recovered}
            reason = "operation_timeout" if operation_expired() else "emulator_process_shutdown_timeout"
            return {"status": "blocked", "reason": reason, "pids": pids}

        if not targets:
            return {"status": "stopped", "adb_recovered": recovered}

        serial = targets[0]["serial"]
        result = runner(["-s", serial, "emu", "kill"])
        if result.returncode != 0:
            reason = "operation_timeout" if operation_expired() else "emulator_shutdown_failed"
            return {"status": "blocked", "reason": reason, "serial": serial}

        deadline = _local_deadline(timeout_s)
        while time.monotonic() < deadline:
            devices, _ = list_devices(runner, allow_recovery=False)
            if not any(row["serial"] == serial and row["state"] == "device" for row in devices):
                residual_pids = pixel_8a_pids()
                if residual_pids:
                    cleanup_s = remaining_timeout(timeout_s)
                    if cleanup_s > 0.0 and terminate_pids(
                        residual_pids,
                        timeout_s=cleanup_s,
                        interval_s=interval_s,
                    ):
                        return {"status": "stopped", "serial": serial, "pids": residual_pids}
                    reason = "operation_timeout" if operation_expired() else "emulator_process_shutdown_timeout"
                    return {"status": "blocked", "reason": reason, "pids": residual_pids}
                return {"status": "stopped", "serial": serial}
            if operation_expired():
                break
            sleep_s = min(interval_s, max(0.0, deadline - time.monotonic()))
            if sleep_s > 0.0:
                time.sleep(sleep_s)

        reason = "operation_timeout" if operation_expired() else "emulator_shutdown_timeout"
        return {"status": "blocked", "reason": reason, "serial": serial}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", nargs="?", choices=("status", "start", "wait", "stop"))
    parser.add_argument("--target", choices=("pixel", "tcl", "emulator", "any"), default="any")
    parser.add_argument("--allow-emulator-fallback", action="store_true")
    parser.add_argument("--timeout", type=float, default=180.0)
    args = parser.parse_args()

    if args.command == "status":
        result = emulator_status(timeout_s=args.timeout)
    elif args.command == "start":
        result = select_target("emulator", False, timeout_s=args.timeout)
    elif args.command == "wait":
        result = wait_command(timeout_s=args.timeout)
    elif args.command == "stop":
        result = stop_pixel_8a(timeout_s=min(args.timeout, 30.0))
    else:
        result = select_target(args.target, args.allow_emulator_fallback, timeout_s=args.timeout)

    print(json.dumps(result, sort_keys=True))
    return 0 if result["status"] in ("ok", "ready", "stopped") else 2


if __name__ == "__main__":
    raise SystemExit(main())
