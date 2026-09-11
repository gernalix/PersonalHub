from __future__ import annotations

import contextlib
import importlib
import io
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

notify = importlib.import_module("telegram_notify.notify")


class TelegramNotifyCliTests(unittest.TestCase):
    def test_file_only_uses_empty_caption_without_title_or_message(self) -> None:
        with tempfile.NamedTemporaryFile() as handle:
            with mock.patch.object(sys, "argv", ["telegram_notify", "--file", handle.name]):
                with mock.patch.object(notify, "send_file") as send_file:
                    self.assertEqual(0, notify.main())

            args, kwargs = send_file.call_args
            self.assertEqual(Path(handle.name), args[0])
            self.assertIsNone(args[1])
            self.assertIsNone(args[2])
            self.assertEqual("", kwargs["caption"])

    def test_invalid_file_is_rejected_before_network(self) -> None:
        missing = "/tmp/telegram-notify-definitely-missing.txt"
        with mock.patch.object(sys, "argv", ["telegram_notify", "--file", missing]):
            with mock.patch.object(notify, "send_file") as send_file:
                with contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(1, notify.main())

        send_file.assert_not_called()

    def test_file_with_title_and_message_preserves_existing_caption_behavior(self) -> None:
        with tempfile.NamedTemporaryFile() as handle:
            with mock.patch.object(sys, "argv", ["telegram_notify", "--file", handle.name, "Title", "Message"]):
                with mock.patch.object(notify, "send_file") as send_file:
                    self.assertEqual(0, notify.main())

            args, kwargs = send_file.call_args
            self.assertEqual(Path(handle.name), args[0])
            self.assertEqual("Title", args[1])
            self.assertEqual("Message", args[2])
            self.assertIsNone(kwargs["caption"])


if __name__ == "__main__":
    unittest.main()
