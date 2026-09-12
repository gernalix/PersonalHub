from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest import mock

import deliver_personalhub_apk as delivery


class DeliverPersonalHubApkTest(unittest.TestCase):
    def test_small_apk_is_sent_directly_to_telegram(self) -> None:
        with tempfile.NamedTemporaryFile() as handle:
            path = Path(handle.name)
            with mock.patch.dict("sys.modules", {"telegram_notify": mock.Mock()}):
                import telegram_notify

                self.assertEqual("telegram_file", delivery.deliver(path, "42", telegram_title="PH"))
                telegram_notify.send_file.assert_called_once()
                telegram_notify.send_message.assert_not_called()

    def test_large_apk_updates_stable_release_and_sends_link(self) -> None:
        with tempfile.NamedTemporaryFile() as handle:
            path = Path(handle.name)
            handle.truncate(delivery.TELEGRAM_LIMIT_BYTES + 1)
            with mock.patch.object(delivery, "publish_release_asset", return_value=("https://github.com/gernalix/PersonalHub/releases/tag/personalhub-dev-apk", True, "PRIVATE")):
                with mock.patch.dict("sys.modules", {"telegram_notify": mock.Mock()}):
                    import telegram_notify

                    result = delivery.deliver(path, "42", telegram_title="PH")

            self.assertEqual("github_release_link visibility=PRIVATE", result)
            telegram_notify.send_file.assert_not_called()
            telegram_notify.send_message.assert_called_once()
            self.assertIn("autenticazione GitHub", telegram_notify.send_message.call_args.args[1])

    def test_release_asset_name_tracks_version_and_commit(self) -> None:
        with tempfile.NamedTemporaryFile(suffix=".apk") as handle:
            calls: list[list[str]] = []

            def fake_run(args: list[str]) -> mock.Mock:
                calls.append(args)
                if args[:3] == ["gh", "repo", "view"]:
                    return mock.Mock(stdout='{"isPrivate": true, "visibility": "PRIVATE"}')
                if args[:3] == ["git", "rev-parse", "--short=12"]:
                    return mock.Mock(stdout="abc123def456\n")
                if args[:4] == ["gh", "release", "view", delivery.DEV_RELEASE_TAG] and "--json" not in args:
                    return mock.Mock(returncode=0, stdout="")
                if args[-1] == "assets":
                    return mock.Mock(stdout='{"assets":[{"name":"old.apk"}]}')
                if args[-1] == "url":
                    return mock.Mock(stdout='{"url":"https://example.test/release"}')
                return mock.Mock(stdout="")

            with mock.patch.object(delivery, "_run", side_effect=fake_run):
                with mock.patch("subprocess.run", return_value=mock.Mock(returncode=0, stdout="")):
                    delivery.publish_release_asset(Path(handle.name), "42")

        upload = next(call for call in calls if call[:3] == ["gh", "release", "upload"])
        self.assertIn("#PersonalHub-42-abc123def456.apk", upload[4])
        self.assertIn(["gh", "release", "delete-asset", delivery.DEV_RELEASE_TAG, "old.apk", "--repo", delivery.REPO, "-y"], calls)


if __name__ == "__main__":
    unittest.main()
