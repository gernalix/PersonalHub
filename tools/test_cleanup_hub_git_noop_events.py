#!/usr/bin/env python3

import sqlite3
import tempfile
import unittest
from pathlib import Path

from cleanup_hub_git_noop_events import compact_copy


class CleanupHubGitNoopEventsTest(unittest.TestCase):
    def test_compact_copy_removes_only_exact_noop_updates(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "source.db"
            output = root / "clean.db"
            conn = sqlite3.connect(source)
            try:
                conn.execute(
                    "CREATE TABLE hub_git_events("
                    "table_name TEXT NOT NULL, operation TEXT NOT NULL, "
                    "before_payload TEXT, after_payload TEXT)"
                )
                conn.executemany(
                    "INSERT INTO hub_git_events VALUES (?,?,?,?)",
                    [
                        ("hub_tags", "UPDATE", "same", "same"),
                        ("hub_tags", "UPDATE", "before", "after"),
                        ("hub_tags", "INSERT", None, "new"),
                    ],
                )
                conn.commit()
            finally:
                conn.close()

            result = compact_copy(source, output)

            self.assertEqual(1, result["noop_updates_deleted"])
            self.assertEqual(0, result["noop_updates_remaining"])
            self.assertEqual(3, result["events_before"])
            self.assertEqual(2, result["events_after"])
            self.assertEqual({"hub_tags": 1}, result["noop_updates_by_table"])

            with sqlite3.connect(source) as original:
                self.assertEqual(
                    3,
                    original.execute("SELECT count(*) FROM hub_git_events").fetchone()[0],
                )
            with sqlite3.connect(output) as compacted:
                rows = compacted.execute(
                    "SELECT operation,before_payload,after_payload FROM hub_git_events"
                ).fetchall()
                self.assertEqual(
                    [("UPDATE", "before", "after"), ("INSERT", None, "new")],
                    rows,
                )


if __name__ == "__main__":
    unittest.main()
