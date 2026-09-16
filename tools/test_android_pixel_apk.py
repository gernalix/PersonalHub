from __future__ import annotations

import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

import android_pixel_apk as installer


class AndroidPixelApkTests(unittest.TestCase):
    def test_resolve_apk_uses_gradle_output_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            apk = out / "46.apk"
            apk.write_bytes(b"apk")
            metadata = out / "output-metadata.json"
            metadata.write_text(json.dumps({"elements": [{"outputFile": "46.apk"}]}), encoding="utf-8")

            self.assertEqual(apk.resolve(), installer.resolve_apk(metadata))

    def test_resolve_apk_rejects_ambiguous_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            for name in ("a.apk", "b.apk"):
                (out / name).write_bytes(b"apk")
            metadata = out / "output-metadata.json"
            metadata.write_text(
                json.dumps({"elements": [{"outputFile": "a.apk"}, {"outputFile": "b.apk"}]}),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(installer.PixelApkError, "exactly one"):
                installer.resolve_apk(metadata)

    def test_resolve_pixel_requires_physical_pixel(self) -> None:
        with mock.patch.object(
            installer.preflight,
            "select_target",
            return_value={"status": "ok", "target": {"kind": "pixel", "serial": "pixel-serial", "model": "Pixel_8a"}},
        ) as select:
            target = installer.resolve_pixel(timeout_s=12)

        self.assertEqual("pixel-serial", target["serial"])
        select.assert_called_once_with("pixel", False, timeout_s=12)

    def test_install_is_serial_scoped_and_honors_timeout(self) -> None:
        completed = subprocess.CompletedProcess([], 0, "Success\n", "")
        with mock.patch.object(installer.preflight, "resolve_tool", return_value="/sdk/adb") as resolve_tool:
            with mock.patch.object(installer.subprocess, "run", return_value=completed) as run:
                detail = installer.install(Path("/tmp/46.apk"), "pixel-serial", timeout_s=180)

        self.assertEqual("Success", detail)
        resolve_tool.assert_called_once_with("adb")
        run.assert_called_once_with(
            ["/sdk/adb", "-s", "pixel-serial", "install", "-r", "/tmp/46.apk"],
            text=True,
            capture_output=True,
            check=False,
            timeout=180.0,
        )

    def test_install_timeout_is_reported_without_retry(self) -> None:
        with mock.patch.object(installer.preflight, "resolve_tool", return_value="/sdk/adb"):
            with mock.patch.object(
                installer.subprocess,
                "run",
                side_effect=subprocess.TimeoutExpired(cmd=["/sdk/adb"], timeout=90),
            ) as run:
                with self.assertRaisesRegex(installer.PixelApkError, "adb_timeout"):
                    installer.install(Path("/tmp/47.apk"), "pixel-serial", timeout_s=90)
        self.assertEqual(run.call_count, 1)


if __name__ == "__main__":
    unittest.main()
