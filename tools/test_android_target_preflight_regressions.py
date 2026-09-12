from __future__ import annotations

import subprocess
import unittest
from subprocess import CompletedProcess
from unittest.mock import patch

import android_target_preflight as preflight


class FakeAdb:
    def __init__(self, responses: dict[tuple[str, ...], list[CompletedProcess[str]]]):
        self.responses = {key: list(value) for key, value in responses.items()}
        self.calls: list[tuple[str, ...]] = []

    def __call__(self, args: list[str]) -> CompletedProcess[str]:
        key = tuple(args)
        self.calls.append(key)
        values = self.responses.get(key)
        if not values:
            return CompletedProcess(args, 0, "", "")
        if len(values) == 1:
            return values[0]
        return values.pop(0)


def proc(stdout: str = "", code: int = 0, stderr: str = "") -> CompletedProcess[str]:
    return CompletedProcess([], code, stdout, stderr)


class FakeProcess:
    def __init__(self, returncode=None):
        self.returncode = returncode

    def poll(self):
        return self.returncode

    def terminate(self):
        self.returncode = 0

    def kill(self):
        self.returncode = -9

    def wait(self, timeout=None):
        if self.returncode is None:
            raise subprocess.TimeoutExpired("fake", timeout)
        return self.returncode


TARGET = {"serial": "emulator-5556", "state": "device", "model": "sdk", "kind": "emulator"}


class AndroidTargetPreflightResidualRegressionTests(unittest.TestCase):
    def test_offline_recovery_is_attempted_once_per_operation(self) -> None:
        offline = proc("List of devices attached\nemulator-5554 offline model:sdk\n")
        adb = FakeAdb({
            ("devices", "-l"): [offline] * 20,
            ("reconnect", "offline"): [proc("reconnected\n")] * 20,
        })
        result = preflight.select_target(
            "emulator",
            False,
            runner=adb,
            start_avd=lambda *_: self.fail("must not launch a second Pixel_8a"),
            process_finder=lambda: [123],
            timeout_s=0.01,
        )
        self.assertEqual("blocked", result["status"])
        self.assertEqual(1, adb.calls.count(("reconnect", "offline")))

    def test_unrelated_offline_avd_does_not_block_pixel_8a_start(self) -> None:
        offline = proc("List of devices attached\nemulator-5554 offline model:sdk\n")
        adb = FakeAdb({
            ("devices", "-l"): [offline, offline],
            ("reconnect", "offline"): [proc("reconnected\n")],
        })
        starts = []
        result = preflight.select_target(
            "emulator",
            False,
            runner=adb,
            start_avd=lambda extra=None: starts.append(extra) or {
                "process": FakeProcess(None),
                "log_path": "/tmp/test-emulator.log",
            },
            waiter=lambda *_args, **_kwargs: TARGET,
            process_finder=lambda: [],
            renderer_check=lambda _path: False,
            timeout_s=1.0,
        )
        self.assertEqual("ok", result["status"])
        self.assertEqual([None], starts)

    def test_status_ignores_unrelated_offline_avd_without_pixel_process(self) -> None:
        offline = proc("List of devices attached\nemulator-5554 offline model:sdk\n")
        adb = FakeAdb({
            ("devices", "-l"): [offline, offline],
            ("reconnect", "offline"): [proc("reconnected\n")],
        })
        with (
            patch.object(preflight, "list_avds", return_value=("/sdk/emulator", ["Pixel_8a", "Other_AVD"])),
            patch.object(preflight, "resolve_tool", return_value="/sdk/adb"),
            patch.object(preflight, "pixel_8a_pids", return_value=[]),
        ):
            result = preflight.emulator_status(adb, timeout_s=1.0)
        self.assertEqual("stopped", result["status"])

    def test_zero_global_timeout_skips_adb_subprocess(self) -> None:
        with patch.object(preflight.subprocess, "run") as run:
            with preflight.operation_scope(0.0):
                result = preflight.run_adb(["devices", "-l"])
        self.assertEqual(124, result.returncode)
        self.assertEqual("operation_timeout", result.stderr)
        run.assert_not_called()

    def test_renderer_retry_cleans_residual_pid_before_fallback(self) -> None:
        starts = []
        terminated = []
        first = FakeProcess(None)
        pid_results = iter([[], [321], []])
        waits = iter([None, TARGET])

        result = preflight.select_target(
            "emulator",
            False,
            runner=FakeAdb({("devices", "-l"): [proc("List of devices attached\n")]}),
            start_avd=lambda extra=None: starts.append(extra) or {
                "process": first if extra is None else FakeProcess(None),
                "log_path": "/tmp/test-emulator.log",
            },
            waiter=lambda *_args, **_kwargs: next(waits),
            renderer_check=lambda _path: True,
            process_finder=lambda: next(pid_results),
            process_terminator=lambda pids, **_kwargs: terminated.append(pids) or True,
            timeout_s=1.0,
        )
        self.assertEqual("ok", result["status"])
        self.assertEqual([[321]], terminated)
        self.assertEqual([None, ["-gpu", "swiftshader_indirect"]], starts)

    def test_renderer_retry_stops_if_residual_pid_survives_cleanup(self) -> None:
        starts = []
        first = FakeProcess(None)
        pid_results = iter([[], [321], [321]])

        result = preflight.select_target(
            "emulator",
            False,
            runner=FakeAdb({("devices", "-l"): [proc("List of devices attached\n")]}),
            start_avd=lambda extra=None: starts.append(extra) or {
                "process": first,
                "log_path": "/tmp/test-emulator.log",
            },
            waiter=lambda *_args, **_kwargs: None,
            renderer_check=lambda _path: True,
            process_finder=lambda: next(pid_results),
            process_terminator=lambda _pids, **_kwargs: True,
            timeout_s=1.0,
        )
        self.assertEqual("blocked", result["status"])
        self.assertEqual("renderer_retry_residual_pixel_8a_process", result["reason"])
        self.assertEqual([None], starts)


if __name__ == "__main__":
    unittest.main()
