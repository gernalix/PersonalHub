import unittest
from pathlib import Path
import hashlib
import subprocess
import tempfile
from unittest import mock

from android_shortcut_alias_smoke import launched_activity, require_installed_artifact, SmokeError, main


class LaunchedActivityTest(unittest.TestCase):
    def test_android_abbreviated_activity_name(self):
        output = "Activity: com.gernalix.personalhub/.soldi.SoldiActivity\n"
        self.assertEqual(
            "com.gernalix.personalhub.soldi.SoldiActivity",
            launched_activity(output, "com.gernalix.personalhub"),
        )

    def test_fully_qualified_feature_activity_name(self):
        output = "Activity: com.gernalix.personalhub/com.example.multitimetracker.MainActivity\n"
        self.assertEqual(
            "com.example.multitimetracker.MainActivity",
            launched_activity(output, "com.gernalix.personalhub"),
        )

    def test_existing_artifact_is_read_only_and_hash_verified(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "70.apk"
            apk.write_bytes(b"immutable artifact")
            digest = hashlib.sha256(apk.read_bytes()).hexdigest()
            with mock.patch("android_shortcut_alias_smoke.run", side_effect=[
                subprocess.CompletedProcess([], 0, "package:/data/app/base.apk\n"),
                subprocess.CompletedProcess([], 0, digest + "  /data/app/base.apk\n"),
            ]) as runner:
                self.assertEqual(digest, require_installed_artifact("adb", "verified", "app", apk))
                self.assertEqual(runner.call_args_list, [
                    mock.call("adb", "-s", "verified", "shell", "pm", "path", "app"),
                    mock.call("adb", "-s", "verified", "shell", "sha256sum", "/data/app/base.apk"),
                ])

    def test_changed_installed_bytes_and_split_packages_are_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "70.apk"
            apk.write_bytes(b"immutable artifact")
            with mock.patch("android_shortcut_alias_smoke.run", side_effect=[
                subprocess.CompletedProcess([], 0, "package:/data/app/base.apk\n"),
                subprocess.CompletedProcess([], 0, "0" * 64 + "  /data/app/base.apk\n"),
            ]):
                with self.assertRaisesRegex(SmokeError, "differs"):
                    require_installed_artifact("adb", "verified", "app", apk)
            with mock.patch("android_shortcut_alias_smoke.run", return_value=
                            subprocess.CompletedProcess([], 0, "package:/base.apk\npackage:/split.apk\n")) as runner:
                with self.assertRaisesRegex(SmokeError, "one installed APK"):
                    require_installed_artifact("adb", "verified", "app", apk)
                self.assertEqual(1, runner.call_count)

    def test_verified_installed_mode_never_installs_or_grants_permissions(self):
        self.run_installed_smoke("ok")

    def test_android_timeout_cannot_pass_from_activity_name_alone(self):
        with self.assertRaisesRegex(SmokeError, "launch failed"):
            self.run_installed_smoke("timeout")

    def run_installed_smoke(self, status):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "70.apk"
            apk.write_bytes(b"immutable artifact")
            alias = "com.gernalix.personalhub.shortcut.Timer"
            activity = "com.example.multitimetracker.MainActivity"
            def output(*args, **kwargs):
                if args[-1] == "get-state":
                    return subprocess.CompletedProcess(args, 0, "device\n")
                return subprocess.CompletedProcess(args, 0,
                    f"Status: {status}\nActivity: com.gernalix.personalhub/{activity}\nComplete\n")
            with mock.patch("sys.argv", ["smoke", "--serial", "verified", "--apk", str(apk), "--already-installed"]), \
                 mock.patch("android_shortcut_alias_smoke.discover_aliases", return_value=[(alias, activity)]), \
                 mock.patch("android_shortcut_alias_smoke.require_installed_artifact") as guard, \
                 mock.patch("android_shortcut_alias_smoke.run", side_effect=output) as runner:
                try:
                    self.assertEqual(0, main())
                finally:
                    guard.assert_called_once()
                    self.assertFalse(any("install" in call.args or "-g" in call.args for call in runner.call_args_list))


if __name__ == "__main__":
    unittest.main()
