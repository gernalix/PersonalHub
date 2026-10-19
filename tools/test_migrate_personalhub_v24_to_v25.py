import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path

from migrate_personalhub_v21_to_v23 import snapshot, validate
from migrate_personalhub_v23_to_v24 import migrate as migrate24
from migrate_personalhub_v24_to_v25 import migrate, rollback
from test_migrate_personalhub_v23_to_v24 import fixture


class CountdownMigrationTest(unittest.TestCase):
    def test_preserves_existing_values_source_bytes_and_exact_rollback(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            legacy, source, output = root / "old.db", root / "source.db", root / "target.db"
            fixture(legacy)
            migrate24(legacy, source)
            with closing(sqlite3.connect(source)) as db:
                for prefix in ("mutation", "hub_git_dirty"):
                    for operation in ("INSERT", "UPDATE", "DELETE"):
                        db.execute(f'CREATE TRIGGER "{prefix}_since_when_counters_{operation}" AFTER {operation} ON since_when_counters BEGIN SELECT 1; END')
                db.commit()
            original = source.read_bytes()
            report = migrate(source, output)
            self.assertEqual("PASS", report["status"])
            self.assertEqual(6, len(report["retired_counter_triggers"]))
            self.assertEqual(original, source.read_bytes())
            with closing(sqlite3.connect(output)) as db:
                validate(db, 25)
                self.assertEqual([("SINCE_WHEN",)], db.execute("SELECT DISTINCT mode FROM since_when_counters").fetchall())
                db.execute("UPDATE since_when_counters SET mode='COUNTDOWN'")
                db.commit()
            again = root / "again.db"
            self.assertEqual(25, migrate(output, again)["source_version"])
            with closing(sqlite3.connect(again)) as db:
                self.assertEqual([("COUNTDOWN",)], db.execute("SELECT DISTINCT mode FROM since_when_counters").fetchall())
            restored = root / "restored.db"
            rollback(output, restored)
            with closing(sqlite3.connect(source)) as before, closing(sqlite3.connect(restored)) as after:
                validate(after, 24)
                self.assertEqual(snapshot(before), snapshot(after))
            with self.assertRaises(ValueError):
                migrate(source, output)

    def test_rejects_invalid_modes_without_publishing_a_target(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            legacy, source, output = root / "old.db", root / "source.db", root / "target.db"
            fixture(legacy)
            migrate24(legacy, source)
            migrate(source, output)
            with closing(sqlite3.connect(output)) as db:
                db.execute("UPDATE since_when_counters SET mode='INVALID'")
                db.commit()
            refused = root / "refused.db"
            with self.assertRaises(ValueError):
                migrate(output, refused)
            self.assertFalse(refused.exists())


if __name__ == "__main__":
    unittest.main()
