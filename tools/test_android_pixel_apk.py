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

    def test_install_is_always_serial_scoped(self) -> None:
        completed = subprocess.CompletedProcess([], 0, "Success\n", "")
        with mock.patch.object(installer.preflight, "run_adb", return_value=completed) as run_adb:
            detail = installer.install(Path("/tmp/46.apk"), "pixel-serial")

        self.assertEqual("Success", detail)
        run_adb.assert_called_once_with(["-s", "pixel-serial", "install", "-r", "/tmp/46.apk"])


if __name__ == "__main__":
    unittest.main()
