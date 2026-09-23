from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

TOOLS = Path(__file__).resolve().parent
if str(TOOLS) not in sys.path:
    sys.path.insert(0, str(TOOLS))

import personalhub_consolidation_watcher as watcher


class Result:
    def __init__(self, returncode: int = 0, stdout: str = "", stderr: str = "") -> None:
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


class ConsolidationWatcherTests(unittest.TestCase):
    def test_unmerged_remote_branches_only_reports_unique_commits(self) -> None:
        with (
            patch.object(
                watcher,
                "remote_non_main_branches",
                return_value=["origin/merged", "origin/pending"],
            ),
            patch.object(
                watcher,
                "_run",
                side_effect=[Result(0), Result(1)],
            ),
        ):
            self.assertEqual(["pending"], watcher.unmerged_remote_branches())

    def test_relation_error_fails_closed(self) -> None:
        with (
            patch.object(
                watcher,
                "remote_non_main_branches",
                return_value=["origin/broken"],
            ),
            patch.object(watcher, "_run", return_value=Result(2, stderr="bad ref")),
        ):
            with self.assertRaises(watcher.ConsolidationError):
                watcher.unmerged_remote_branches()

    def test_state_round_trip_keeps_sha_dedup_fields(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "state.json"
            payload = {
                "schema_version": 1,
                "built_sha": "a" * 40,
                "installed_sha": "a" * 40,
                "notified_sha": "a" * 40,
            }
            watcher._atomic_state(payload, path)
            self.assertEqual(payload, watcher._read_state(path))
            self.assertEqual(payload, json.loads(path.read_text(encoding="utf-8")))


if __name__ == "__main__":
    unittest.main()
