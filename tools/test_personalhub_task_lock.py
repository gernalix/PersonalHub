from __future__ import annotations

import json
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import personalhub_task_lock as lock


class PersonalHubTaskLockTest(unittest.TestCase):
    def test_second_acquire_is_blocked_without_waiting(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "ph.lock"
            missing_db = Path(temp_dir) / "missing.sqlite"
            self.assertEqual(0, lock.acquire(path, "618472", 3600, missing_db))
            self.assertEqual(75, lock.acquire(path, "other", 3600, missing_db))
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
            self.assertEqual(
                0,
                lock.acquire(path, "618472", 1, Path(temp_dir) / "missing.sqlite"),
            )
            self.assertEqual("618472", json.loads(path.read_text(encoding="utf-8"))["prompt_id"])
            self.assertEqual(1, len(list(Path(temp_dir).glob("ph.lock.stale.*"))))

    def test_terminal_roadmap_prompt_lease_is_recovered_without_waiting_for_ttl(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            path = root / "ph.lock"
            roadmap_db = root / "roadmap.sqlite"
            with sqlite3.connect(roadmap_db) as connection:
                connection.execute("CREATE TABLE prompts(prompt_id TEXT PRIMARY KEY, status TEXT NOT NULL)")
                connection.execute(
                    "INSERT INTO prompts(prompt_id,status) VALUES(?,?)",
                    ("old", "blocked"),
                )

            path.write_text(
                json.dumps({
                    "prompt_id": "old",
                    "created_at": lock._now(),
                    "host": "another-host",
                    "pid": 99999999,
                }),
                encoding="utf-8",
            )
            self.assertEqual(0, lock.acquire(path, "618472", 3600, roadmap_db))
            self.assertEqual("618472", json.loads(path.read_text(encoding="utf-8"))["prompt_id"])
            self.assertEqual(1, len(list(root.glob("ph.lock.stale.*"))))

    def test_dead_same_host_owner_is_recovered_without_waiting_for_ttl(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            path = root / "ph.lock"
            path.write_text(
                json.dumps({
                    "prompt_id": "running",
                    "created_at": lock._now(),
                    "host": lock.socket.gethostname(),
                    "pid": 424242,
                }),
                encoding="utf-8",
            )
            with mock.patch.object(lock, "_pid_alive", return_value=False):
                self.assertEqual(
                    0,
                    lock.acquire(path, "618472", 3600, root / "missing.sqlite"),
                )
            self.assertEqual("618472", json.loads(path.read_text(encoding="utf-8"))["prompt_id"])
            self.assertEqual(1, len(list(root.glob("ph.lock.stale.*"))))

    def test_unexpired_remote_lease_is_not_removed_when_prompt_state_is_unknown(self) -> None:
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
            self.assertEqual(
                75,
                lock.acquire(path, "618472", 3600, Path(temp_dir) / "missing.sqlite"),
            )
            self.assertEqual("remote", json.loads(path.read_text(encoding="utf-8"))["prompt_id"])


if __name__ == "__main__":
    unittest.main()
