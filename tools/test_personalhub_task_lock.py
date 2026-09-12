from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import personalhub_task_lock as lock


class PersonalHubTaskLockTest(unittest.TestCase):
    def test_second_acquire_is_blocked_without_waiting(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "ph.lock"
            self.assertEqual(0, lock.acquire(path, "618472", 3600))
            self.assertEqual(75, lock.acquire(path, "other", 3600))
            self.assertEqual(0, lock.release(path, "618472"))

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

    def test_unexpired_lease_is_not_removed_even_when_pid_is_unknown(self) -> None:
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
            self.assertEqual(75, lock.acquire(path, "618472", 3600))
            self.assertEqual("remote", json.loads(path.read_text(encoding="utf-8"))["prompt_id"])


if __name__ == "__main__":
    unittest.main()
