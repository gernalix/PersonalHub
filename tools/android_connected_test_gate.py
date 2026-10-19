#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

FAILURE_MARKERS = (
    "INSTRUMENTATION_FAILED",
    "INSTRUMENTATION_RESULT: shortMsg=",
    "INSTALL_FAILED",
    "DELETE_FAILED",
    "INSTRUMENTATION_STATUS_CODE: -2",
    "FAILURES!!!",
    "Test run failed to complete",
    "No tests found",
)

# Gradle's connected* tasks can enumerate *all* attached devices. ANDROID_SERIAL
# alone is not a reliable selector for the Android Gradle Plugin.
_GRADLE_CONNECTED_TASK = re.compile(
    r"(?<![a-z0-9_])connected(?:[a-z0-9]*androidtest|check)(?![a-z0-9_])", re.IGNORECASE
)


def target_error(command: list[str], *, allow_physical_device: bool = False) -> str | None:
    gradle_connected = any(_GRADLE_CONNECTED_TASK.search(arg) for arg in command)
    gradle_connected = gradle_connected and any(
        "gradle" in arg.lower() for arg in command
    )
    adb_indices = [i for i, arg in enumerate(command) if Path(arg).name == "adb"]
    adb_index = adb_indices[0] if len(adb_indices) == 1 else None
    adb_instrumentation = ("instrument" in command and adb_index is not None) or any(
        re.search(r"\badb\b.*\binstrument\b", arg, re.IGNORECASE)
        for arg in command
    )

    if gradle_connected:
        # Even an explicitly configured ANDROID_SERIAL does not prove that
        # connectedDebugAndroidTest will ignore a second physical device.
        try:
            result = subprocess.run(
                ["adb", "devices"], capture_output=True, text=True, timeout=10, check=True
            )
        except (OSError, subprocess.SubprocessError) as exc:
            return f"Cannot verify connected Android targets: {exc}"
        devices: list[tuple[str, str]] = []
        for line in result.stdout.splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2 and parts[1] in ("device", "offline", "unauthorized"):
                devices.append((parts[0], parts[1]))
        if len(devices) != 1 or not devices[0][0].startswith("emulator-") or devices[0][1] != "device":
            return (
                "Gradle connected Android tests require exactly one online emulator "
                "and no attached physical/offline device; use explicit adb -s "
                "instrumentation when other devices are attached"
            )
        if os.environ.get("ANDROID_SERIAL") not in (None, "", devices[0][0]):
            return "ANDROID_SERIAL conflicts with the only connected emulator"
        return None

    if adb_instrumentation:
        if adb_index is None:
            return "Instrumentation must use a direct adb -s <serial> invocation"
        adb_args = command[adb_index + 1 :]
        before_shell = adb_args[: adb_args.index("shell")] if "shell" in adb_args else adb_args
        selectors = [
            before_shell[i + 1]
            for i, arg in enumerate(before_shell[:-1])
            if arg == "-s"
        ]
        if len(selectors) != 1:
            return "Instrumentation must select exactly one target with adb -s <serial>"
        if not selectors[0].startswith("emulator-") and not allow_physical_device:
            return (
                "Physical Android instrumentation is blocked unless explicitly "
                "opted in with --allow-physical-device after the required safety notifications"
            )
    return None


def run(command: list[str], *, allow_physical_device: bool = False,
        history_context: dict | None = None, history_log: Path | None = None) -> int:
    if not command:
        raise ValueError("missing command")
    error = target_error(command, allow_physical_device=allow_physical_device)
    if error:
        print(f"FAIL-CLOSED: {error}", file=sys.stderr)
        return 2
    if history_context is not None and history_log is None:
        raise ValueError("History recording requires its own --history-log path")
    if history_context is not None:
        # Validate recording metadata before touching a device.
        if not history_context.get("device_kind"):
            raise ValueError("History context requires device_kind")
        history_log = history_log.expanduser().resolve()
        history_log.parent.mkdir(parents=True, exist_ok=True)
    process = subprocess.Popen(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    assert process.stdout is not None
    detected: str | None = None
    output: list[str] = []
    with process.stdout:
        for line in process.stdout:
            if history_context is not None:
                output.append(line)
            sys.stdout.write(line)
            sys.stdout.flush()
            if detected is None:
                detected = next((marker for marker in FAILURE_MARKERS if marker in line), None)
    returncode = process.wait()
    if history_context is not None:
        import android_test_history as history
        descriptor = os.open(history_log, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | getattr(os, "O_NOFOLLOW", 0), 0o600)
        with os.fdopen(descriptor, "w") as stream:
            os.fchmod(stream.fileno(), 0o600)
            stream.write("".join(output))
        context = dict(history_context, process_exit_code=returncode or (2 if detected is not None else 0))
        try:
            saved = history.record(history.DEFAULT_DB, history_log, "instrumentation", context)
            print("QA history: " + json.dumps(saved, sort_keys=True))
        except Exception as exc:
            print(f"FAIL-CLOSED: cannot persist QA history ({type(exc).__name__})", file=sys.stderr)
            return returncode or 3
    if returncode != 0:
        return returncode
    if detected is not None:
        print(f"FAIL-CLOSED: connected Android test emitted {detected}", file=sys.stderr)
        return 2
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Fail-closed connected Android test gate for device targeting and test errors."
    )
    parser.add_argument(
        "--allow-physical-device",
        action="store_true",
        help="Permit explicitly serial-targeted physical adb instrumentation only after safety notifications",
    )
    parser.add_argument("command", nargs=argparse.REMAINDER)
    parser.add_argument("--history-context", type=Path, help="Non-secret device/build metadata JSON for SQLite history")
    parser.add_argument("--history-log", type=Path, help="Evidence log written and imported by this gate")
    args = parser.parse_args(argv)
    command = list(args.command)
    if command and command[0] == "--":
        command = command[1:]
    if not command:
        parser.error("command required after --")
    context = json.loads(args.history_context.read_text()) if args.history_context else None
    return run(command, allow_physical_device=args.allow_physical_device,
               history_context=context, history_log=args.history_log)


if __name__ == "__main__":
    raise SystemExit(main())
