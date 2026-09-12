#!/usr/bin/env python3
"""Run the complete emulator-infrastructure unit gate exactly once."""

from __future__ import annotations

import subprocess
import sys
import unittest
from pathlib import Path

sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parents[1]
TOOLS = Path(__file__).resolve().parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

TEST_MODULES = (
    "test_android_target_preflight",
    "test_android_target_preflight_regressions",
    "test_android_emulator_control",
)


def tracked_python_cache() -> list[str]:
    completed = subprocess.run(
        ["git", "-C", str(ROOT), "ls-files"],
        text=True,
        capture_output=True,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(completed.stderr.strip() or "git ls-files failed")
    return [
        path
        for path in completed.stdout.splitlines()
        if "/__pycache__/" in f"/{path}" or path.endswith((".pyc", ".pyo"))
    ]


def main() -> int:
    tracked_cache = tracked_python_cache()
    if tracked_cache:
        print("EMULATOR_STACK=FAIL")
        print("tracked Python cache artifacts: " + ", ".join(tracked_cache))
        return 1

    suite = unittest.defaultTestLoader.loadTestsFromNames(TEST_MODULES)
    result = unittest.TextTestRunner(verbosity=1).run(suite)
    status = "PASS" if result.wasSuccessful() else "FAIL"
    print(f"EMULATOR_STACK={status} tests={result.testsRun} failures={len(result.failures)} errors={len(result.errors)}")
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
