#!/usr/bin/env python3
"""Focused lossless migration checks for the additive Room schemas."""
from contextlib import closing
from pathlib import Path
import sqlite3
import tempfile
import unittest

import migrate_personalhub_v21_to_v23 as migration


class MigrationTest(unittest.TestCase):
    def test_v21_v22_and_v23_preserve_existing_rows(self):
        for version in (21, 22, 23):
            with self.subTest(version=version), tempfile.TemporaryDirectory() as directory:
                source = Path(directory) / "source.db"
                target = Path(directory) / "target.db"
                schema = migration.schema(version)
                with closing(sqlite3.connect(source)) as db:
                    for entity in schema["entities"]:
                        table = entity["tableName"]
                        db.execute(entity["createSql"].replace("${TABLE_NAME}", table))
                        for index in entity.get("indices", []):
                            db.execute(index["createSql"].replace("${TABLE_NAME}", table))
                    db.execute("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                    db.execute("INSERT INTO room_master_table VALUES (42,?)", (schema["identityHash"],))
                    db.execute("INSERT INTO hub_generation(id,generation) VALUES (1,7)")
                    db.execute(f"PRAGMA user_version={version}")
                    db.commit()
                report = migration.migrate(source, target)
                self.assertEqual("PASS", report["status"])
                self.assertEqual(23, report["target_version"])
                with closing(sqlite3.connect(target)) as db:
                    self.assertEqual((7,), db.execute("SELECT generation FROM hub_generation WHERE id=1").fetchone())
                    self.assertEqual(23, db.execute("PRAGMA user_version").fetchone()[0])

    def test_wrong_identity_fails_without_output(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.db"
            target = Path(directory) / "target.db"
            with closing(sqlite3.connect(source)) as db:
                db.execute("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                db.execute("INSERT INTO room_master_table VALUES (42,'wrong')")
                db.execute("PRAGMA user_version=21")
                db.commit()
            with self.assertRaises(ValueError):
                migration.migrate(source, target)
            self.assertFalse(target.exists())


if __name__ == "__main__":
    unittest.main()
