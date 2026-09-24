#!/usr/bin/env python3
from __future__ import annotations

import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
import migrate_personalhub_v20_to_v21 as migration

HEALTH_VIEWS = [
    "v_health_measurement_turnaround",
    "v_health_sample_turnaround",
    "v_health_turnaround_stats",
    "v_health_samples",
    "v_health_measurements",
    "v_health_sample_measurements",
    "v_health_journal",
    "v_health_ai_evidence",
    "v_health_timeline",
]


def create_v20_fixture(path: Path) -> None:
    schema = json.loads(
        migration.SCHEMA_21_PATH.read_text(encoding="utf-8")
    )["database"]
    db = sqlite3.connect(path)
    try:
        db.execute("PRAGMA foreign_keys=OFF")
        for entity in schema["entities"]:
            table = entity["tableName"]
            db.execute(entity["createSql"].replace("${TABLE_NAME}", table))
            for index in entity.get("indices", []):
                db.execute(index["createSql"].replace("${TABLE_NAME}", table))
        for query in schema["setupQueries"]:
            db.execute(query)

        for table in sorted(migration.HEALTH_TABLES):
            db.execute(f'CREATE TABLE "{table}" (id TEXT PRIMARY KEY)')
        for view in HEALTH_VIEWS:
            db.execute(f'CREATE VIEW "{view}" AS SELECT id FROM health_events')

        db.execute("INSERT INTO hub_generation(id,generation) VALUES(1,20)")
        db.execute(
            "INSERT INTO hub_preferences(namespace,json) VALUES('keep','{\"value\":1}')"
        )
        db.execute(
            "INSERT INTO hub_entity_bindings VALUES"
            "('keep-binding','people','person','keep','ACTIVE',1)"
        )
        db.execute(
            "INSERT INTO hub_entity_bindings VALUES"
            "('salute-binding','salute','measurement','health-1','ACTIVE',1)"
        )
        db.execute(
            "INSERT INTO hub_contexts VALUES('mixed',NULL,'Mixed',1,1)"
        )
        db.execute(
            "INSERT INTO hub_contexts VALUES('health-only',NULL,'Health only',1,1)"
        )
        db.execute(
            "INSERT INTO hub_context_members VALUES"
            "('mixed','keep-binding','member',0)"
        )
        db.execute(
            "INSERT INTO hub_context_members VALUES"
            "('mixed','salute-binding','health',1)"
        )
        db.execute(
            "INSERT INTO hub_context_members VALUES"
            "('health-only','salute-binding','health',0)"
        )
        db.execute(
            "INSERT INTO hub_tags VALUES"
            "('keep-tag','global','TAG','Keep','keep',NULL,NULL,NULL,1,1,0,0,1,NULL,0,NULL)"
        )
        db.execute(
            "INSERT INTO hub_tags VALUES"
            "('salute-tag','salute','TAG','Health','health',NULL,NULL,NULL,1,1,0,0,0,NULL,0,NULL)"
        )
        db.execute(
            "INSERT INTO hub_tag_aliases VALUES('salute-tag','salute','H','h')"
        )
        db.execute(
            "INSERT INTO hub_tag_assignments VALUES"
            "('keep-binding','keep-tag',1,'test')"
        )
        db.execute(
            "INSERT INTO hub_tag_assignments VALUES"
            "('salute-binding','salute-tag',1,'test')"
        )
        db.execute(
            "INSERT INTO hub_tag_parents VALUES('salute-tag','keep-tag')"
        )
        db.execute(
            "INSERT INTO hub_saved_tag_filters VALUES"
            "('health-filter','salute','Health','{}',1,1)"
        )
        db.execute(
            "INSERT INTO hub_activity_log"
            "(id,occurred_at,module_id,action,origin,is_system,source_table,"
            "payload_version,app_version,reversible,status) "
            "VALUES('health-log',1,'salute','write','test',0,'health_events',1,59,0,'ACTIVE')"
        )
        db.execute(
            "INSERT INTO hub_sync_pending VALUES('health_events','1',1)"
        )
        db.execute(
            "INSERT INTO hub_sync_known VALUES('health_events','1')"
        )
        db.execute(
            "CREATE TRIGGER hub_dirty_health_events_INSERT "
            "AFTER INSERT ON health_events BEGIN "
            "UPDATE hub_generation SET generation=generation+1 WHERE id=1; END"
        )
        db.execute(
            "CREATE TRIGGER hub_dirty_hub_preferences_UPDATE "
            "AFTER UPDATE ON hub_preferences BEGIN "
            "UPDATE hub_generation SET generation=generation+1 WHERE id=1; END"
        )
        db.execute(
            "INSERT OR REPLACE INTO room_master_table(id,identity_hash) VALUES(42,?)",
            (migration.SOURCE_IDENTITY_HASH,),
        )
        db.execute("PRAGMA user_version=20")
        db.commit()
    finally:
        db.close()


class PersonalHub20To21MigrationTest(unittest.TestCase):
    def test_migration_removes_salute_and_preserves_other_data(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "personalhub-v20.db"
            output = Path(directory) / "personalhub-v21.db"
            create_v20_fixture(source)

            report = migration.migrate(source, output)

            self.assertEqual("PASS", report["status"])
            self.assertEqual(20, report["source_version"])
            self.assertEqual(21, report["target_version"])
            self.assertGreater(report["preserved_tables_verified"], 0)
            db = sqlite3.connect(output)
            try:
                self.assertEqual(
                    21, db.execute("PRAGMA user_version").fetchone()[0]
                )
                self.assertEqual(
                    migration.TARGET_IDENTITY_HASH,
                    db.execute(
                        "SELECT identity_hash FROM room_master_table WHERE id=42"
                    ).fetchone()[0],
                )
                self.assertEqual(
                    '{"value":1}',
                    db.execute(
                        "SELECT json FROM hub_preferences WHERE namespace='keep'"
                    ).fetchone()[0],
                )
                self.assertEqual(
                    0,
                    db.execute(
                        "SELECT count(*) FROM hub_entity_bindings "
                        "WHERE module_id='salute'"
                    ).fetchone()[0],
                )
                self.assertEqual(
                    1,
                    db.execute(
                        "SELECT count(*) FROM hub_contexts WHERE id='mixed'"
                    ).fetchone()[0],
                )
                self.assertEqual(
                    1,
                    db.execute(
                        "SELECT count(*) FROM hub_context_members "
                        "WHERE context_id='mixed'"
                    ).fetchone()[0],
                )
                self.assertEqual(
                    0,
                    db.execute(
                        "SELECT count(*) FROM hub_contexts WHERE id='health-only'"
                    ).fetchone()[0],
                )
                self.assertEqual(
                    0,
                    db.execute(
                        "SELECT count(*) FROM sqlite_master "
                        "WHERE type='table' AND name LIKE 'health_%'"
                    ).fetchone()[0],
                )
                self.assertEqual(
                    0,
                    db.execute(
                        "SELECT count(*) FROM sqlite_master "
                        "WHERE type='view' AND name LIKE 'v_health_%'"
                    ).fetchone()[0],
                )
                self.assertEqual(
                    0,
                    db.execute(
                        "SELECT count(*) FROM sqlite_master WHERE type='trigger'"
                    ).fetchone()[0],
                )
                self.assertEqual(
                    [("ok",)], db.execute("PRAGMA quick_check").fetchall()
                )
                self.assertEqual(
                    [], db.execute("PRAGMA foreign_key_check").fetchall()
                )
            finally:
                db.close()
            self.assertTrue(
                Path(str(output) + ".validation.json").is_file()
            )

    def test_non_v20_source_is_rejected_without_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "personalhub-v21.db"
            output = Path(directory) / "should-not-exist.db"
            create_v20_fixture(source)
            db = sqlite3.connect(source)
            try:
                db.execute("PRAGMA user_version=21")
                db.commit()
            finally:
                db.close()

            with self.assertRaises(migration.MigrationError):
                migration.migrate(source, output)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
