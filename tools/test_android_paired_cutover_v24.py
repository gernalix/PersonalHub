#!/usr/bin/env python3
"""Host-only cutover safety tests. Never interact with ADB or a real package."""
from __future__ import annotations

import argparse
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from android_paired_cutover_v24 import (
    CutoverBlocked,
    assert_still_frozen,
    execute,
    require_notification_receipt,
    require_single_profile,
    sha,
    stage_and_swap,
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


if __name__ == "__main__":
    unittest.main()
