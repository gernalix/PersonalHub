from __future__ import annotations

import io
import subprocess
import sys
import unittest
from unittest.mock import patch

import android_connected_test_gate as gate


class AndroidConnectedTestGateTests(unittest.TestCase):
    def test_success_without_failure_markers_passes(self) -> None:
        self.assertEqual(0, gate.run([sys.executable, "-c", 'print("BUILD SUCCESSFUL")']))

    def test_instrumentation_failed_is_fail_closed_even_when_child_exits_zero(self) -> None:
        self.assertEqual(
            2,
            gate.run([sys.executable, "-c", 'print("INSTRUMENTATION_FAILED: process crashed"); print("BUILD SUCCESSFUL")']),
        )

    def test_delete_failed_is_fail_closed_even_when_child_exits_zero(self) -> None:
        self.assertEqual(
            2,
            gate.run([sys.executable, "-c", 'print("DELETE_FAILED_INTERNAL_ERROR"); print("BUILD SUCCESSFUL")']),
        )

    def test_runner_setup_failure_is_fail_closed_even_when_child_exits_zero(self) -> None:
        self.assertEqual(
            2,
            gate.run([sys.executable, "-c", 'print("INSTRUMENTATION_RESULT: shortMsg=Unable to find instrumentation"); print("BUILD SUCCESSFUL")']),
        )

    def test_nonzero_child_status_is_preserved(self) -> None:
        self.assertEqual(7, gate.run([sys.executable, "-c", "raise SystemExit(7)"]))

    @staticmethod
    def fake_devices(*lines: str) -> subprocess.CompletedProcess[str]:
        return subprocess.CompletedProcess(
            ["adb", "devices"], 0,
            stdout="List of devices attached\n" + "\n".join(lines) + "\n",
            stderr="",
        )

    def test_gradle_with_pixel_and_emulator_fails_before_launch(self) -> None:
        command = ["./gradlew", ":feature:multitimetracker:connectedDebugAndroidTest"]
        with (
            patch.object(gate.subprocess, "run", return_value=self.fake_devices(
                "emulator-5554 device", "192.168.1.37:46207 device"
            )),
            patch.object(gate.subprocess, "Popen") as launch,
            patch.dict(gate.os.environ, {"ANDROID_SERIAL": "emulator-5554"}),
        ):
            self.assertEqual(2, gate.run(command))
            launch.assert_not_called()

    def test_gradle_with_only_physical_target_fails(self) -> None:
        with (
            patch.object(gate.subprocess, "run", return_value=self.fake_devices(
                "192.168.1.37:46207 device"
            )),
            patch.object(gate.subprocess, "Popen") as launch,
        ):
            self.assertEqual(2, gate.run(["./gradlew", ":app:connectedQaAndroidTest"]))
            launch.assert_not_called()

    def test_gradle_with_offline_target_fails(self) -> None:
        with (
            patch.object(gate.subprocess, "run", return_value=self.fake_devices(
                "emulator-5554 device", "192.168.1.37:46207 offline"
            )),
            patch.object(gate.subprocess, "Popen") as launch,
        ):
            self.assertEqual(2, gate.run(["./gradlew", "connectedDebugAndroidTest"]))
            launch.assert_not_called()

    def test_gradle_with_no_adb_fails_closed(self) -> None:
        with (
            patch.object(gate.subprocess, "run", side_effect=FileNotFoundError("adb")),
            patch.object(gate.subprocess, "Popen") as launch,
        ):
            self.assertEqual(2, gate.run(["./gradlew", "connectedDebugAndroidTest"]))
            launch.assert_not_called()

    def test_gradle_with_only_emulator_passes(self) -> None:
        with (
            patch.object(gate.subprocess, "run", return_value=self.fake_devices("emulator-5554 device")),
            patch.object(gate.subprocess, "Popen") as launch,
            patch.dict(gate.os.environ, {"ANDROID_SERIAL": "emulator-5554"}),
        ):
            launch.return_value.stdout = io.StringIO("BUILD SUCCESSFUL\n")
            launch.return_value.wait.return_value = 0
            self.assertEqual(
                0, gate.run(["timeout", "50m", "./gradlew", ":feature:luoghi:connectedDebugAndroidTest"])
            )
            launch.assert_called_once()

    def test_nested_gradle_connected_command_is_guarded(self) -> None:
        with (
            patch.object(gate.subprocess, "run", return_value=self.fake_devices(
                "emulator-5554 device", "192.168.1.37:46207 device"
            )),
            patch.object(gate.subprocess, "Popen") as launch,
        ):
            self.assertEqual(2, gate.run(["bash", "-lc", "./gradlew :app:connectedQaAndroidTest"]))
            launch.assert_not_called()

    def test_nested_adb_instrumentation_is_rejected(self) -> None:
        with patch.object(gate.subprocess, "Popen") as launch:
            self.assertEqual(2, gate.run(["bash", "-lc", "adb -s emulator-5554 shell am instrument -w pkg/runner"]))
            launch.assert_not_called()

    def test_unscoped_direct_adb_instrumentation_rejected(self) -> None:
        with patch.object(gate.subprocess, "Popen") as launch:
            self.assertEqual(2, gate.run(["adb", "shell", "am", "instrument", "-w", "pkg/runner"]))
            launch.assert_not_called()

    def test_explicit_emulator_instrumentation_allowed_with_pixel_attached(self) -> None:
        with patch.object(gate.subprocess, "Popen") as launch:
            launch.return_value.stdout = io.StringIO("OK (1 test)\n")
            launch.return_value.wait.return_value = 0
            self.assertEqual(
                0, gate.run(["adb", "-s", "emulator-5554", "shell", "am", "instrument", "-w", "pkg/runner"])
            )
            launch.assert_called_once()

    def test_explicit_physical_instrumentation_requires_opt_in(self) -> None:
        cmd = ["adb", "-s", "192.168.1.37:46207", "shell", "am", "instrument", "-w", "pkg/runner"]
        with patch.object(gate.subprocess, "Popen") as launch:
            self.assertEqual(2, gate.run(cmd))
            launch.assert_not_called()
            launch.return_value.stdout = io.StringIO("OK (1 test)\n")
            launch.return_value.wait.return_value = 0
            self.assertEqual(0, gate.run(cmd, allow_physical_device=True))


if __name__ == "__main__":
    unittest.main()
