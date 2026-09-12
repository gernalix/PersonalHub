from __future__ import annotations

import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

import smoke_large_apk_delivery as smoke


class LargeApkDeliverySmokeTest(unittest.TestCase):
    def test_validate_real_large_apk_requires_manifest_and_size(self) -> None:
        with tempfile.NamedTemporaryFile(suffix=".apk") as handle:
            path = Path(handle.name)
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("AndroidManifest.xml", b"manifest")
            with mock.patch.object(smoke.delivery, "TELEGRAM_LIMIT_BYTES", 1):
                smoke.validate_real_large_apk(path)

    def test_validate_rejects_zip_without_android_manifest(self) -> None:
        with tempfile.NamedTemporaryFile(suffix=".apk") as handle:
            path = Path(handle.name)
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("classes.dex", b"dex")
            with mock.patch.object(smoke.delivery, "TELEGRAM_LIMIT_BYTES", 1):
                with self.assertRaisesRegex(ValueError, "AndroidManifest"):
                    smoke.validate_real_large_apk(path)

    def test_run_smoke_uses_isolated_release_and_verifies_asset(self) -> None:
        with tempfile.NamedTemporaryFile(suffix=".apk") as handle:
            path = Path(handle.name)
            with mock.patch.object(smoke, "validate_real_large_apk"):
                with mock.patch.object(smoke, "_run", return_value=mock.Mock(stdout="")):
                    with mock.patch.object(
                        smoke.delivery,
                        "_head_sha",
                        return_value="abc123def4567890",
                    ):
                        with mock.patch.object(
                            smoke.delivery,
                            "deliver",
                            return_value="github_release_link visibility=PRIVATE",
                        ) as deliver:
                            with mock.patch.object(
                                smoke,
                                "_release_assets",
                                return_value=["PersonalHub-42-abc123def456.apk"],
                            ):
                                result = smoke.run_smoke(path, "42")

        self.assertEqual("PASS", result["status"])
        deliver.assert_called_once_with(
            path,
            "42",
            telegram_title="PersonalHub APK >50 MiB smoke test",
            release_tag=smoke.SMOKE_RELEASE_TAG,
            release_title=smoke.SMOKE_RELEASE_TITLE,
        )


if __name__ == "__main__":
    unittest.main()
