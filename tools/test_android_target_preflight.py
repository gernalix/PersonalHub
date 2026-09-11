from __future__ import annotations

import unittest
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

    def test_offline_target_is_not_eligible(self) -> None:
        rows = preflight.parse_devices(
            "List of devices attached\nP offline product:akita model:Pixel_8a device:akita\n"
        )
        self.assertEqual("offline", rows[0]["state"])

    def test_single_start_server_recovery_then_reuses_existing_target(self) -> None:
        adb = FakeAdb({
            ("devices", "-l"): [
                proc(["devices", "-l"], 1, "", "daemon down"),
                proc(["devices", "-l"], 0, "List of devices attached\nT device model:TCL_6102H\n"),
            ],
            ("start-server",): [proc(["start-server"], 0)],
        })

        result = preflight.select_target("tcl", False, runner=adb, start_avd=lambda runner: None)

        self.assertEqual("ok", result["status"])
        self.assertEqual("T", result["target"]["serial"])
        self.assertTrue(result["adb_recovered"])
        self.assertEqual(1, adb.calls.count(("start-server",)))

    def test_pixel_without_fallback_stays_blocked_when_only_emulator_is_live(self) -> None:
        adb = FakeAdb({
            ("devices", "-l"): [proc(
                ["devices", "-l"],
                0,
                "List of devices attached\nemulator-5554 device product:sdk model:sdk_gphone64_x86_64\n",
            )],
        })

        result = preflight.select_target("pixel", False, runner=adb, start_avd=lambda runner: None)

        self.assertEqual({"status": "blocked", "reason": "pixel_physical_required_but_absent", "adb_recovered": False}, result)

    def test_pixel_with_fallback_can_reuse_live_emulator(self) -> None:
        adb = FakeAdb({
            ("devices", "-l"): [proc(
                ["devices", "-l"],
                0,
                "List of devices attached\nemulator-5554 device product:sdk model:sdk_gphone64_x86_64\n",
            )],
        })

        result = preflight.select_target("pixel", True, runner=adb, start_avd=lambda runner: self.fail("must not start AVD"))

        self.assertEqual("ok", result["status"])
        self.assertEqual("emulator-5554", result["target"]["serial"])

    def test_started_avd_matches_by_emulator_console_name(self) -> None:
        adb = FakeAdb({
            ("devices", "-l"): [
                proc(["devices", "-l"], 0, "List of devices attached\n"),
                proc(["devices", "-l"], 0, "List of devices attached\nemulator-5554 device product:sdk model:sdk_gphone64_x86_64\n"),
            ],
            ("-s", "emulator-5554", "emu", "avd", "name"): [proc(["-s", "emulator-5554", "emu", "avd", "name"], 0, "Pixel_8a\nOK\n")],
        })
        started = []

        result = preflight.select_target(
            "emulator",
            False,
            runner=adb,
            start_avd=lambda runner: started.append("Pixel_8a"),
        )

        self.assertEqual("ok", result["status"])
        self.assertEqual(["Pixel_8a"], started)
        self.assertEqual("Pixel_8a", result["avd_started"])


if __name__ == "__main__":
    unittest.main()
