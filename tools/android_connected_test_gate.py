#!/usr/bin/env python3
from __future__ import annotations

import argparse
import subprocess
import sys

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


def run(command: list[str]) -> int:
    if not command:
        raise ValueError("missing command")
    process = subprocess.Popen(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    assert process.stdout is not None
    detected: str | None = None
    with process.stdout:
        for line in process.stdout:
            sys.stdout.write(line)
            sys.stdout.flush()
            if detected is None:
                detected = next((marker for marker in FAILURE_MARKERS if marker in line), None)
    returncode = process.wait()
    if returncode != 0:
        return returncode
    if detected is not None:
        print(f"FAIL-CLOSED: connected Android test emitted {detected}", file=sys.stderr)
        return 2
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Run a connected Android test command and reject known false-success instrumentation output."
    )
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    command = list(args.command)
    if command and command[0] == "--":
        command = command[1:]
    if not command:
        parser.error("command required after --")
    return run(command)


if __name__ == "__main__":
    raise SystemExit(main())
