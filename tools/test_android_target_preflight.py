from __future__ import annotations

import unittest
import subprocess
import tempfile
from pathlib import Path
from subprocess import CompletedProcess

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


def proc(args: list[str], code: int, stdout: str = "", stderr: str = "") -> CompletedProcess[str]:
    return CompletedProcess(args, code, stdout, stderr)


class FakeProcess:
    def __init__(self, returncode=None):
        self.returncode = returncode
        self.terminated = False
        self.killed = False

    def poll(self):
        return self.returncode

    def terminate(self):
        self.terminated = True
        self.returncode = 0

    def kill(self):
        self.killed = True
        self.returncode = -9

    def wait(self, timeout=None):
        if self.returncode is None:
            raise subprocess.TimeoutExpired("fake", timeout)
        return self.returncode


class SlowFakeProcess(FakeProcess):
    def terminate(self):
        self.terminated = True

    def wait(self, timeout=None):
        if self.returncode is None:
            raise subprocess.TimeoutExpired("fake", timeout)
        return self.returncode


class AndroidTargetPreflightTests(unittest.TestCase):
    def test_classifies_pixel_tcl_and_emulator_without_transport_id(self) -> None:
        rows = preflight.parse_devices(
            "List of devices attached\n"
            "P device product:akita model:Pixel_8a device:akita transport_id:7\n"
            "T device product:tcl model:TCL_6102H device:6102H transport_id:8\n"
            "emulator-5554 device product:sdk model:sdk_gphone64_x86_64 transport_id:9\n"
        )
        self.assertEqual(["pixel", "tcl", "emulator"], [row["kind"] for row in rows])
        self.assertTrue(all("transport_id" not in row for row in rows))

    def test_any_prefers_emulator_over_physical_devices(self) -> None:
        live = [
            {"serial": "P", "state": "device", "model": "Pixel_8a", "kind": "pixel"},
            {"serial": "T", "state": "device", "model": "TCL_6102H", "kind": "tcl"},
            {"serial": "emulator-5554", "state": "device", "model": "sdk", "kind": "emulator"},
        ]
        self.assertEqual("emulator-5554", preflight.choose_target(live, "any", False)["serial"])

    def test_single_start_server_recovery_then_reuses_existing_target(self) -> None:
        adb = FakeAdb({
            ("devices", "-l"): [
                proc(["devices", "-l"], 1, "", "daemon down"),
                proc(["devices", "-l"], 0, "List of devices attached\nT device model:TCL_6102H\n"),
            ],
            ("start-server",): [proc(["start-server"], 0)],
        })
        result = preflight.select_target("tcl", False, runner=adb, start_avd=lambda *_: {})
        self.assertEqual("ok", result["status"])
        self.assertEqual("T", result["target"]["serial"])
        self.assertTrue(result["adb_recovered"])
        self.assertEqual(1, adb.calls.count(("start-server",)))

    def test_pixel_without_fallback_stays_blocked_when_only_emulator_is_live(self) -> None:
        adb = FakeAdb({
            ("devices", "-l"): [proc(
                ["devices", "-l"], 0,
                "List of devices attached\nemulator-5554 device product:sdk model:sdk_gphone64_x86_64\n",
            )],
        })
        result = preflight.select_target("pixel", False, runner=adb, start_avd=lambda *_: {})
        self.assertEqual(
            {"status": "blocked", "reason": "pixel_physical_required_but_absent", "adb_recovered": False},
            result,
        )

    def test_pixel_with_fallback_can_reuse_booted_live_emulator(self) -> None:
        adb = FakeAdb({
            ("devices", "-l"): [proc(
                ["devices", "-l"], 0,
                "List of devices attached\nemulator-5554 device product:sdk model:sdk_gphone64_x86_64\n",
            )],
            ("-s", "emulator-5554", "shell", "getprop", "sys.boot_completed"): [
                proc([], 0, "1\n")
            ],
            ("-s", "emulator-5554", "emu", "avd", "name"): [
                proc([], 0, "Pixel_8a\nOK\n")
            ],
        })
        result = preflight.select_target(
            "pixel", True, runner=adb,
            start_avd=lambda *_: self.fail("must not start AVD"),
        )
        self.assertEqual("ok", result["status"])
        self.assertEqual("emulator-5554", result["target"]["serial"])

    def test_waiter_requires_boot_completed(self) -> None:
        adb = FakeAdb({
            ("devices", "-l"): [proc(
                ["devices", "-l"], 0,
                "List of devices attached\nemulator-5554 device model:sdk\n",
            )],
            ("-s", "emulator-5554", "emu", "avd", "name"): [proc([], 0, "Pixel_8a\nOK\n")],
            ("-s", "emulator-5554", "shell", "getprop", "sys.boot_completed"): [
                proc([], 0, "1\n")
            ],
        })
        row = preflight.wait_for_pixel_8a_emulator(adb, timeout_s=0.1, interval_s=0)
        self.assertIsNotNone(row)
        self.assertEqual("emulator-5554", row["serial"])

    def test_renderer_failure_gets_one_software_retry(self) -> None:
        starts = []
        fake_target = {"serial": "emulator-5554", "state": "device", "model": "sdk", "kind": "emulator"}

        def start(extra=None):
            starts.append(extra)
            return {
                "process": FakeProcess(None),
                "log_path": "/tmp/software" if extra else "/tmp/first",
            }

        waits = iter([None, fake_target])

        def waiter(*args, **kwargs):
            return next(waits)

        result = preflight.select_target(
            "emulator",
            False,
            runner=FakeAdb({("devices", "-l"): [proc([], 0, "List of devices attached\n")]}),
            start_avd=start,
            waiter=waiter,
            renderer_check=lambda path: path == "/tmp/first",
            process_finder=lambda: [],
        )
        self.assertEqual([None, ["-gpu", "swiftshader_indirect"]], starts)
        self.assertEqual("ok", result["status"])
        self.assertEqual("swiftshader_indirect", result["renderer_fallback"])

    def test_offline_pixel_is_not_treated_as_stopped(self) -> None:
        adb = FakeAdb({
            ("devices", "-l"): [
                proc([], 0, "List of devices attached\nemulator-5554 offline model:sdk\n"),
                proc([], 0, "List of devices attached\nemulator-5554 offline model:sdk\n"),
            ],
            ("reconnect", "offline"): [proc([], 0, "reconnected\n")],
        })
        original_list_avds = preflight.list_avds
        original_resolve_tool = preflight.resolve_tool
        original_pids = preflight.pixel_8a_pids
        try:
            preflight.list_avds = lambda: ("/sdk/emulator", ["Pixel_8a"])
            preflight.resolve_tool = lambda name: f"/sdk/{name}"
            preflight.pixel_8a_pids = lambda: [123]
            result = preflight.emulator_status(adb)
        finally:
            preflight.list_avds = original_list_avds
            preflight.resolve_tool = original_resolve_tool
            preflight.pixel_8a_pids = original_pids
        self.assertEqual("booting/offline", result["status"])
        self.assertEqual({"adb_state": "offline", "sys.boot_completed": "unknown"}, result["readiness"])

    def test_offline_recovery_success_reuses_target_without_launch(self) -> None:
        adb = FakeAdb({
            ("devices", "-l"): [
                proc([], 0, "List of devices attached\nemulator-5554 offline model:sdk\n"),
                proc([], 0, "List of devices attached\nemulator-5554 device model:sdk\n"),
            ],
            ("reconnect", "offline"): [proc([], 0, "reconnected\n")],
            ("-s", "emulator-5554", "emu", "avd", "name"): [proc([], 0, "Pixel_8a\nOK\n")],
            ("-s", "emulator-5554", "shell", "getprop", "sys.boot_completed"): [proc([], 0, "1\n")],
        })
        result = preflight.select_target(
            "emulator",
            False,
            runner=adb,
            start_avd=lambda *_: self.fail("must not start AVD after offline recovery"),
            process_finder=lambda: [123],
        )
        self.assertEqual("ok", result["status"])
        self.assertTrue(result["adb_recovered"])
        self.assertEqual(1, adb.calls.count(("reconnect", "offline")))

    def test_offline_recovery_failure_does_not_launch_second_avd(self) -> None:
        adb = FakeAdb({
            ("devices", "-l"): [
                proc([], 0, "List of devices attached\nemulator-5554 offline model:sdk\n"),
                proc([], 0, "List of devices attached\nemulator-5554 offline model:sdk\n"),
                proc([], 0, "List of devices attached\nemulator-5554 offline model:sdk\n"),
            ],
            ("reconnect", "offline"): [proc([], 0, "reconnected\n")],
        })
        result = preflight.select_target(
            "emulator",
            False,
            runner=adb,
            start_avd=lambda *_: self.fail("must not start another Pixel_8a"),
            waiter=lambda *_args, **_kwargs: None,
            process_finder=lambda: [123],
        )
        self.assertEqual("blocked", result["status"])
        self.assertEqual("Pixel_8a_booting_or_offline", result["reason"])

    def test_booting_pixel_does_not_start_second_avd(self) -> None:
        adb = FakeAdb({
            ("devices", "-l"): [proc([], 0, "List of devices attached\nemulator-5554 device model:sdk\n")],
            ("-s", "emulator-5554", "emu", "avd", "name"): [proc([], 0, "Pixel_8a\nOK\n")],
            ("-s", "emulator-5554", "shell", "getprop", "sys.boot_completed"): [proc([], 0, "0\n")],
        })
        result = preflight.select_target(
            "emulator",
            False,
            runner=adb,
            start_avd=lambda *_: self.fail("must not start while Pixel_8a is booting"),
            waiter=lambda *_args, **_kwargs: None,
            process_finder=lambda: [123],
        )
        self.assertEqual("blocked", result["status"])
        self.assertEqual("Pixel_8a_booting_or_offline", result["reason"])

    def test_boot_timeout_does_not_leave_two_processes(self) -> None:
        starts = []

        def start(extra=None):
            starts.append(extra)
            return {"process": FakeProcess(None), "log_path": "/tmp/no-renderer-failure"}

        result = preflight.select_target(
            "emulator",
            False,
            runner=FakeAdb({("devices", "-l"): [proc([], 0, "List of devices attached\n")]}),
            start_avd=start,
            waiter=lambda *_args, **_kwargs: None,
            renderer_check=lambda _path: False,
            process_finder=lambda: [],
        )
        self.assertEqual("blocked", result["status"])
        self.assertEqual([None], starts)

    def test_renderer_failure_ignores_normal_gpu_mentions(self) -> None:
        with tempfile.NamedTemporaryFile("w", encoding="utf-8") as handle:
            handle.write("Vulkan device selected. GPU renderer initialized with EGL/OpenGL support.\n")
            handle.flush()
            self.assertFalse(preflight.renderer_failure(handle.name))

    def test_renderer_failure_detects_concrete_gpu_errors(self) -> None:
        with tempfile.NamedTemporaryFile("w", encoding="utf-8") as handle:
            handle.write("ERROR: Vulkan renderer failed to initialize GPU backend.\n")
            handle.flush()
            self.assertTrue(preflight.renderer_failure(handle.name))

    def test_renderer_fallback_terminates_first_process_before_retry(self) -> None:
        first = FakeProcess(None)
        starts = []
        target = {"serial": "emulator-5554", "state": "device", "model": "sdk", "kind": "emulator"}

        def start(extra=None):
            starts.append(extra)
            return {"process": first if extra is None else FakeProcess(None), "log_path": str(Path(tempfile.gettempdir()) / "log")}

        waits = iter([None, target])
        result = preflight.select_target(
            "emulator",
            False,
            runner=FakeAdb({("devices", "-l"): [proc([], 0, "List of devices attached\n")]}),
            start_avd=start,
            waiter=lambda *_args, **_kwargs: next(waits),
            renderer_check=lambda _path: True,
            process_finder=lambda: [],
        )
        self.assertEqual("ok", result["status"])
        self.assertTrue(first.terminated)
        self.assertEqual([None, ["-gpu", "swiftshader_indirect"]], starts)

    def test_adb_timeout_returns_controlled_result(self) -> None:
        original_resolve_tool = preflight.resolve_tool
        original_run = preflight.subprocess.run
        try:
            preflight.resolve_tool = lambda _name: "/sdk/adb"

            def timeout_run(*_args, **kwargs):
                raise subprocess.TimeoutExpired("adb", kwargs.get("timeout"))

            preflight.subprocess.run = timeout_run
            result = preflight.run_adb(["devices", "-l"])
        finally:
            preflight.resolve_tool = original_resolve_tool
            preflight.subprocess.run = original_run
        self.assertEqual(124, result.returncode)
        self.assertIn("adb_timeout", result.stderr)

    def test_emulator_list_timeout_returns_blocked_status(self) -> None:
        original_resolve_tool = preflight.resolve_tool
        original_run = preflight.subprocess.run
        try:
            preflight.resolve_tool = lambda name: f"/sdk/{name}"

            def timeout_run(*_args, **kwargs):
                raise subprocess.TimeoutExpired("emulator", kwargs.get("timeout"))

            preflight.subprocess.run = timeout_run
            result = preflight.emulator_status(FakeAdb({}))
        finally:
            preflight.resolve_tool = original_resolve_tool
            preflight.subprocess.run = original_run
        self.assertEqual("blocked", result["status"])
        self.assertEqual("emulator_list_avds_timeout", result["reason"])

    def test_noncanonical_live_emulator_is_not_reused(self) -> None:
        adb = FakeAdb({
            ("devices", "-l"): [proc(
                ["devices", "-l"], 0,
                "List of devices attached\nemulator-5554 device model:sdk\n",
            )],
            ("-s", "emulator-5554", "emu", "avd", "name"): [proc([], 0, "Other_AVD\nOK\n")],
        })
        starts = []
        expected = {"serial": "emulator-5556", "state": "device", "model": "sdk", "kind": "emulator"}
        result = preflight.select_target(
            "emulator", False, runner=adb,
            start_avd=lambda *_: starts.append(True) or {"process": FakeProcess(None), "log_path": "/tmp/log"},
            waiter=lambda *_args, **_kwargs: expected,
            process_finder=lambda: [],
        )
        self.assertEqual([True], starts)
        self.assertEqual("emulator-5556", result["target"]["serial"])

    def test_status_reports_ready_only_for_booted_pixel_8a(self) -> None:
        adb = FakeAdb({
            ("devices", "-l"): [proc([], 0, "List of devices attached\nemulator-5554 device model:sdk\n")],
            ("-s", "emulator-5554", "emu", "avd", "name"): [proc([], 0, "Pixel_8a\nOK\n")],
            ("-s", "emulator-5554", "shell", "getprop", "sys.boot_completed"): [proc([], 0, "1\n")],
        })
        original_list_avds = preflight.list_avds
        original_resolve_tool = preflight.resolve_tool
        try:
            preflight.list_avds = lambda: ("/sdk/emulator", ["Pixel_8a"])
            preflight.resolve_tool = lambda name: f"/sdk/{name}"
            result = preflight.emulator_status(adb)
        finally:
            preflight.list_avds = original_list_avds
            preflight.resolve_tool = original_resolve_tool
        self.assertEqual("ready", result["status"])
        self.assertEqual({"adb_state": "device", "sys.boot_completed": "1"}, result["readiness"])

    def test_stop_is_idempotent_when_pixel_8a_is_absent(self) -> None:
        adb = FakeAdb({("devices", "-l"): [proc([], 0, "List of devices attached\n")]})
        original_pids = preflight.pixel_8a_pids
        try:
            preflight.pixel_8a_pids = lambda: []
            self.assertEqual({"status": "stopped", "adb_recovered": False}, preflight.stop_pixel_8a(adb))
        finally:
            preflight.pixel_8a_pids = original_pids

    def test_stop_kills_pixel_8a_and_waits_until_absent(self) -> None:
        adb = FakeAdb({
            ("devices", "-l"): [
                proc([], 0, "List of devices attached\nemulator-5554 device model:sdk\n"),
                proc([], 0, "List of devices attached\n"),
            ],
            ("-s", "emulator-5554", "emu", "avd", "name"): [proc([], 0, "Pixel_8a\nOK\n")],
            ("-s", "emulator-5554", "emu", "kill"): [proc([], 0, "OK\n")],
        })
        original_pids = preflight.pixel_8a_pids
        try:
            preflight.pixel_8a_pids = lambda: []
            result = preflight.stop_pixel_8a(adb, timeout_s=0.1, interval_s=0)
            self.assertEqual("stopped", result["status"])
            self.assertIn(("-s", "emulator-5554", "emu", "kill"), adb.calls)
        finally:
            preflight.pixel_8a_pids = original_pids

    def test_stop_terminates_offline_pixel_8a_by_process(self) -> None:
        adb = FakeAdb({
            ("devices", "-l"): [
                proc([], 0, "List of devices attached\nemulator-5554 offline model:sdk\n"),
                proc([], 0, "List of devices attached\nemulator-5554 offline model:sdk\n"),
            ],
            ("reconnect", "offline"): [proc([], 0, "reconnected\n")],
        })
        original_pids = preflight.pixel_8a_pids
        original_terminate = preflight.terminate_pids
        try:
            preflight.pixel_8a_pids = lambda: [123]
            terminated = []
            preflight.terminate_pids = lambda pids, **_kwargs: terminated.append(pids) or True
            result = preflight.stop_pixel_8a(adb, timeout_s=0.1, interval_s=0)
        finally:
            preflight.pixel_8a_pids = original_pids
            preflight.terminate_pids = original_terminate
        self.assertEqual("stopped", result["status"])
        self.assertEqual([[123]], terminated)


if __name__ == "__main__":
    unittest.main()
