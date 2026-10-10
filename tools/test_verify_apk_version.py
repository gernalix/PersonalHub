from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import verify_apk_version as gate


class FinalApkVersionTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "version.txt").write_text("64\n", encoding="utf-8")
        self.apk = self.root / "64.apk"
        self.apk.write_bytes(b"fake APK for mocked analyzer")

    def inspect(self, *, package: str = gate.PACKAGE_ID, code: str = "64", name: str = "64"):
        manifest = {"application-id": package, "version-code": code, "version-name": name}
        calls = []

        def fake_run(args, **kwargs):
            calls.append(args)
            return subprocess.CompletedProcess(args, 0, manifest[args[2]] + "\n", "")

        with mock.patch.object(gate, "_apkanalyzer", return_value="/sdk/apkanalyzer"):
            with mock.patch.object(gate.subprocess, "run", side_effect=fake_run):
                value = gate.verify_final_apk(self.apk, declared_version="64", repo_root=self.root)
        return value, calls

    def test_matching_actual_manifest_passes(self) -> None:
        result, calls = self.inspect()
        self.assertEqual({"application-id": gate.PACKAGE_ID, "version-code": "64", "version-name": "64"}, result)
        self.assertEqual(["application-id", "version-code", "version-name"], [args[2] for args in calls])

    def test_wrong_package_version_code_or_version_name_blocks(self) -> None:
        for field, kwargs in [
            ("application-id", {"package": "com.example.other"}),
            ("version-code", {"code": "63"}),
            ("version-name", {"name": "63"}),
        ]:
            with self.subTest(field=field):
                with self.assertRaisesRegex(gate.ApkVersionError, field):
                    self.inspect(**kwargs)

    def test_declared_version_mismatch_blocks_before_analyzer(self) -> None:
        with mock.patch.object(gate, "_apkanalyzer") as analyzer:
            with self.assertRaisesRegex(gate.ApkVersionError, "declared APK version"):
                gate.verify_final_apk(self.apk, declared_version="63", repo_root=self.root)
            analyzer.assert_not_called()

    def test_missing_analyzer_is_a_blocker(self) -> None:
        with mock.patch.object(gate, "_apkanalyzer", side_effect=gate.ApkVersionError("apkanalyzer unavailable")):
            with self.assertRaisesRegex(gate.ApkVersionError, "unavailable"):
                gate.verify_final_apk(self.apk, repo_root=self.root)

    def test_unreadable_manifest_blocks(self) -> None:
        with mock.patch.object(gate, "_apkanalyzer", return_value="/sdk/apkanalyzer"):
            with mock.patch.object(
                gate.subprocess,
                "run",
                side_effect=subprocess.CalledProcessError(1, ["apkanalyzer"]),
            ):
                with self.assertRaisesRegex(gate.ApkVersionError, "could not inspect"):
                    gate.verify_final_apk(self.apk, repo_root=self.root)

    def test_missing_file_and_bad_canonical_version_block(self) -> None:
        with self.assertRaisesRegex(gate.ApkVersionError, "missing or invalid"):
            gate.verify_final_apk(self.root / "missing.apk", repo_root=self.root)
        (self.root / "version.txt").write_text("not-a-number", encoding="utf-8")
        with self.assertRaisesRegex(gate.ApkVersionError, "invalid canonical"):
            gate.verify_final_apk(self.apk, repo_root=self.root)


if __name__ == "__main__":
    unittest.main()
