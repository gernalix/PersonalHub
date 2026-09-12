from __future__ import annotations

import unittest
from subprocess import CompletedProcess
from unittest.mock import patch

import android_emulator_control as control


TARGET = {"serial": "emulator-5554", "state": "device", "model": "sdk", "kind": "emulator"}


class AndroidEmulatorControlTests(unittest.TestCase):
    def test_failed_start_cleans_only_new_processes(self) -> None:
        pid_reads = iter([[100], [100, 200], [100]])
        with (
            patch.object(control.preflight, "pixel_8a_pids", side_effect=lambda: next(pid_reads)),
            patch.object(control.preflight, "select_target", return_value={"status": "blocked", "reason": "emulator_start_failed"}),
            patch.object(control.preflight, "terminate_pids", return_value=True) as terminate,
        ):
            result = control.start(timeout_s=1.0)
        terminate.assert_called_once_with([200], timeout_s=5.0, interval_s=0.2)
        self.assertTrue(result["failed_start_cleanup"]["cleaned"])
        self.assertEqual([], result["failed_start_cleanup"]["residual_pids"])

    def test_failed_start_never_kills_preexisting_process(self) -> None:
        pid_reads = iter([[100], [100]])
        with (
            patch.object(control.preflight, "pixel_8a_pids", side_effect=lambda: next(pid_reads)),
            patch.object(control.preflight, "select_target", return_value={"status": "blocked", "reason": "Pixel_8a_booting_or_offline"}),
            patch.object(control.preflight, "terminate_pids") as terminate,
        ):
            result = control.start(timeout_s=1.0)
        terminate.assert_not_called()
        self.assertEqual("Pixel_8a_booting_or_offline", result["reason"])

    def test_status_does_not_claim_offline_row_as_canonical_target(self) -> None:
        raw = {
            "status": "booting/offline",
            "pids": [123],
            "target": {"serial": "emulator-5554", "state": "offline", "kind": "emulator", "model": "sdk"},
        }
        with patch.object(control.preflight, "emulator_status", return_value=raw):
            result = control.status(timeout_s=1.0)
        self.assertNotIn("target", result)
        self.assertEqual(["emulator-5554"], [row["serial"] for row in result["emulators"]])

    def test_smoke_returns_one_structured_pass(self) -> None:
        stops = iter([{"status": "stopped"}, {"status": "stopped"}])
        pids = iter([[], [321], []])
        with (
            patch.object(control, "stop", side_effect=lambda **_kwargs: next(stops)),
            patch.object(control, "start", return_value={"status": "ok", "target": TARGET}),
            patch.object(control.preflight, "run_adb", return_value=CompletedProcess([], 0, "1\n", "")),
            patch.object(control.preflight, "pixel_8a_pids", side_effect=lambda: next(pids)),
        ):
            result = control.smoke(timeout_s=1.0)
        self.assertEqual("ok", result["status"])
        self.assertEqual(
            {"boot_completed": True, "single_pixel_8a_process": True, "final_stop_clean": True},
            result["checks"],
        )
        self.assertEqual([], result["residual_pids"])


if __name__ == "__main__":
    unittest.main()
