#!/usr/bin/env python3
"""Host-only cutover safety tests. Never interact with ADB or a real package."""
from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
import unittest
from unittest import mock
from pathlib import Path

from android_paired_cutover_v24 import (
    CutoverBlocked,
    Pixel,
    assert_still_frozen,
    execute,
    require_notification_receipt,
    require_single_profile,
    sha,
    stage_and_swap,
    paired_rollback,
)


class FakePixel:
    def __init__(self, root: Path, fail_command: str | None = None):
        self.root = root
        self.fail_command = fail_command
        self.active = False
        (root / "databases").mkdir()
        (root / "files").mkdir()

    def frozen(self):
        return not self.active

    def app(self, command: str, input_bytes=None) -> str:
        if self.fail_command and self.fail_command in command:
            raise OSError("injected cutover failure")
        out = subprocess.run(
            ["sh", "-c", command], cwd=self.root, input=input_bytes,
            capture_output=True, check=True,
        )
        return out.stdout.decode().strip()


class PairedCutoverTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="ph-cutover-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def baseline(self, fail_command=None):
        fake = FakePixel(self.root, fail_command)
        db = self.root / "databases/personalhub.db"
        wal = self.root / "databases/personalhub.db-wal"
        db.write_bytes(b"old coherent database fixture")
        wal.write_bytes(b"old WAL data")
        return fake, {
            "databases/personalhub.db": sha(db),
            "databases/personalhub.db-wal": sha(wal),
        }

    def prepared(self):
        path = self.root / "prepared-v24.db"
        path.write_bytes(b"fully validated new version fixture")
        return path, {"output_sha256": sha(path)}

    def test_frozen_source_and_wal_must_match_snapshot(self):
        fake, expected = self.baseline()
        assert_still_frozen(fake, expected)
        (self.root / "databases/personalhub.db-wal").write_bytes(b"concurrent write")
        with self.assertRaises(CutoverBlocked):
            assert_still_frozen(fake, expected)

    def test_resumed_process_blocks_cutover(self):
        fake, expected = self.baseline()
        fake.active = True
        with self.assertRaisesRegex(CutoverBlocked, "resumed"):
            assert_still_frozen(fake, expected)

    def test_swap_preserves_original_db_and_wal_for_paired_rollback(self):
        fake, expected = self.baseline()
        prepared, report = self.prepared()
        destination = stage_and_swap(fake, prepared, report, expected, "test123")
        self.assertEqual((self.root / "databases/personalhub.db").read_bytes(), prepared.read_bytes())
        for name in ("personalhub.db", "personalhub.db-wal"):
            self.assertTrue((self.root / destination / name).is_file())
        self.assertFalse((self.root / "databases/personalhub.db-wal").exists())

    def test_v25_publish_failure_restores_original_db_and_wal(self):
        fake, expected = self.baseline("mv databases/.ph-v25-")
        prepared, report = self.prepared()
        with self.assertRaises(OSError):
            stage_and_swap(fake, prepared, report, expected, "testv25", target_version=25)
        assert_still_frozen(fake, expected)

    def test_failure_before_first_move_never_removes_live_db(self):
        fake, expected = self.baseline("mv databases/personalhub.db ")
        prepared, report = self.prepared()
        with self.assertRaises(OSError):
            stage_and_swap(fake, prepared, report, expected, "test124")
        assert_still_frozen(fake, expected)

    def test_failure_during_publish_restores_all_original_files(self):
        fake, expected = self.baseline("mv databases/.ph-v24-")
        prepared, report = self.prepared()
        with self.assertRaises(OSError):
            stage_and_swap(fake, prepared, report, expected, "test125")
        assert_still_frozen(fake, expected)

    def test_staged_hash_is_checked_before_moving_original(self):
        fake, expected = self.baseline()
        prepared, report = self.prepared()
        report["output_sha256"] = "f" * 64
        with self.assertRaisesRegex(CutoverBlocked, "hash mismatch"):
            stage_and_swap(fake, prepared, report, expected, "test126")
        assert_still_frozen(fake, expected)

    def test_paired_rollback_uses_explicit_downgrade_then_verifies_the_old_apk(self):
        old_apk = self.root / "previous.apk"
        old_apk.write_bytes(b"verified previous APK")
        device = mock.Mock(serial="pixel-serial")
        device.installed_apk.return_value = "/data/app/previous/base.apk"
        device.shell.return_value = sha(old_apk) + "  /data/app/previous/base.apk"
        with mock.patch("android_paired_cutover_v24.install_apk") as install:
            paired_rollback(device, "files/rollback", old_apk, "rollback123")
        install.assert_called_once_with(old_apk, "pixel-serial", timeout_s=120, paired_rollback=True)
        device.freeze.assert_called_once()
        device.enable.assert_called_once()

    def test_single_profile_and_unfinished_switch(self):
        with self.assertRaisesRegex(CutoverBlocked, "registry unavailable"):
            require_single_profile(b"")
        xml = (
            b'<map><string name="profiles_json">[{"id":"personal"}]</string>'
            b'<string name="active_profile">personal</string></map>'
        )
        require_single_profile(xml)
        with self.assertRaises(CutoverBlocked):
            require_single_profile(xml.replace(b'personal"}]', b'personal"},{"id":"work"}]'))
        with self.assertRaises(CutoverBlocked):
            require_single_profile(xml.replace(b"</map>", b'<string name="pending_switch_to">work</string></map>'))

    def test_notification_receipt_is_fail_closed(self):
        receipt = self.root / "pre.json"
        for invalid in ({"phase": "post", "fedora": True, "telegram": True},
                        {"phase": "pre", "fedora": True, "telegram": False}):
            receipt.write_text(json.dumps(invalid))
            with self.assertRaises(CutoverBlocked):
                require_notification_receipt(receipt)
        receipt.write_text(json.dumps({"phase": "pre", "fedora": True, "telegram": True}))
        require_notification_receipt(receipt)

    def test_without_both_production_flags_no_device_is_contacted(self):
        receipt = self.root / "pre.json"
        receipt.write_text(json.dumps({"phase": "pre", "fedora": True, "telegram": True}))
        args = argparse.Namespace(
            pre_notification=receipt, execute=False, allow_production=False,
        )
        with self.assertRaisesRegex(CutoverBlocked, "Both --execute"):
            execute(args)


class WidgetPreservingFreezeTests(unittest.TestCase):
    def pixel(self):
        with mock.patch("android_paired_cutover_v24.select_target", return_value={
            "status": "ok", "target": {"serial": "pixel-serial", "kind": "pixel"},
        }), mock.patch("android_paired_cutover_v24.resolve_tool", return_value="adb"):
            pixel = Pixel("pixel-serial")
        pixel.shell = mock.Mock()
        pixel.frozen = mock.Mock(return_value=True)
        return pixel

    def test_freeze_and_restore_preserve_enabled_state_and_widget_providers(self):
        for enabled in (0, 1):
            with self.subTest(enabled=enabled):
                pixel = self.pixel()
                pixel.package_state = mock.Mock(side_effect=[
                    (False, enabled), (True, enabled), (False, enabled),
                ])
                pixel.freeze()
                pixel.enable()
                self.assertEqual(pixel.shell.call_args_list, [
                    mock.call("pm", "suspend", "--user", "0", "com.gernalix.personalhub"),
                    mock.call("am", "force-stop", "com.gernalix.personalhub"),
                    mock.call("pm", "unsuspend", "--user", "0", "com.gernalix.personalhub"),
                ])
                self.assertFalse(pixel._suspension_owned)

    def test_existing_suspension_is_not_cleared_by_failure_cleanup(self):
        pixel = self.pixel()
        pixel.package_state = mock.Mock(return_value=(True, 0))
        with self.assertRaisesRegex(CutoverBlocked, "another operation"):
            pixel.freeze()
        pixel.enable()
        pixel.shell.assert_not_called()

    def test_preexisting_disabled_state_is_not_overridden(self):
        pixel = self.pixel()
        pixel.package_state = mock.Mock(return_value=(False, 3))
        with self.assertRaisesRegex(CutoverBlocked, "already disabled"):
            pixel.freeze()
        pixel.enable()
        pixel.shell.assert_not_called()

    def test_failed_suspension_verification_is_fail_closed(self):
        pixel = self.pixel()
        pixel.package_state = mock.Mock(side_effect=[(False, 0), (False, 0)])
        with self.assertRaisesRegex(CutoverBlocked, "not verified"):
            pixel.freeze()
        self.assertTrue(pixel._suspension_owned)

    def test_lost_suspend_response_still_allows_owned_cleanup(self):
        pixel = self.pixel()
        pixel.package_state = mock.Mock(return_value=(False, 0))
        pixel.shell.side_effect = [OSError("response lost after applying suspension"), ""]
        with self.assertRaises(OSError):
            pixel.freeze()
        pixel.enable()
        self.assertFalse(pixel._suspension_owned)

    def test_running_process_blocks_cutover_even_when_suspended(self):
        pixel = self.pixel()
        pixel.package_state = mock.Mock(side_effect=[(False, 0), (True, 0)])
        pixel.frozen.return_value = False
        with self.assertRaisesRegex(CutoverBlocked, "not verified"):
            pixel.freeze()

    def test_failed_unsuspend_does_not_claim_restoration(self):
        pixel = self.pixel()
        pixel.package_state = mock.Mock(side_effect=[(False, 0), (True, 0), (True, 0)])
        pixel.freeze()
        with self.assertRaisesRegex(CutoverBlocked, "restoration"):
            pixel.enable()
        self.assertTrue(pixel._suspension_owned)

    def test_readback_uses_user_zero_and_rejects_missing_state(self):
        pixel = self.pixel()
        pixel.shell.return_value = (
            "  User 0: installed=true suspended=false stopped=true enabled=1\n"
            "  User 10: installed=true suspended=true stopped=true enabled=3\n"
        )
        self.assertEqual((False, 1), pixel.package_state())
        for invalid in ("User 10: suspended=false enabled=0\n", "User 0: installed=true\n"):
            pixel.shell.return_value = invalid
            with self.assertRaises(CutoverBlocked):
                pixel.package_state()


if __name__ == "__main__":
    unittest.main()
