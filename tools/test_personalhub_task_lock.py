from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import personalhub_task_lock as lock


class PersonalHubTaskLockTest(unittest.TestCase):
    def test_second_acquire_is_blocked_when_owner_is_running(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "ph.lock"
            self.assertEqual(0, lock.acquire(path, "618472", 3600))
            with patch.object(lock, "_remote_prompt_status", return_value="running"):
                self.assertEqual(75, lock.acquire(path, "other", 3600))
            self.assertEqual(0, lock.release(path, "618472"))

    def test_same_prompt_reacquire_is_idempotent(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "ph.lock"
            self.assertEqual(0, lock.acquire(path, "618472", 3600))
            self.assertEqual(0, lock.acquire(path, "618472", 3600))
            self.assertEqual("618472", json.loads(path.read_text(encoding="utf-8"))["prompt_id"])

    def test_terminal_owner_is_recovered_before_ttl(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "ph.lock"
            path.write_text(
                json.dumps({
                    "prompt_id": "962109",
                    "created_at": lock._now(),
                    "host": lock.socket.gethostname(),
                    "pid": 99999999,
                }),
                encoding="utf-8",
            )
            with patch.object(lock, "_remote_prompt_status", return_value="superseded"):
                self.assertEqual(0, lock.acquire(path, "521404", 3600))
            self.assertEqual("521404", json.loads(path.read_text(encoding="utf-8"))["prompt_id"])
            self.assertEqual(1, len(list(Path(temp_dir).glob("ph.lock.stale.*"))))

    def test_expired_lease_is_recovered_safely(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "ph.lock"
            path.write_text(
                json.dumps({
                    "prompt_id": "old",
                    "created_at": 1,
                    "host": lock.socket.gethostname(),
                    "pid": 1,
                }),
                encoding="utf-8",
            )
            self.assertEqual(0, lock.acquire(path, "618472", 1))
            self.assertEqual("618472", json.loads(path.read_text(encoding="utf-8"))["prompt_id"])
            self.assertEqual(1, len(list(Path(temp_dir).glob("ph.lock.stale.*"))))

    def test_unexpired_lease_is_not_removed_when_remote_status_is_unknown(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "ph.lock"
            path.write_text(
                json.dumps({
                    "prompt_id": "remote",
                    "created_at": lock._now(),
                    "host": "another-host",
                    "pid": 99999999,
                }),
                encoding="utf-8",
            )
            with patch.object(lock, "_remote_prompt_status", return_value=None):
                self.assertEqual(75, lock.acquire(path, "618472", 3600))
            self.assertEqual("remote", json.loads(path.read_text(encoding="utf-8"))["prompt_id"])


if __name__ == "__main__":
    unittest.main()
