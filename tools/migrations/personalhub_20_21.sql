-- PersonalHub external database migration: schema 20 -> 21.
-- Execute via tools/migrate_personalhub_v20_to_v21.py, which snapshots the
-- source coherently, suspends runtime triggers and validates the result.
PRAGMA foreign_keys=OFF;
BEGIN IMMEDIATE;

CREATE TEMP TABLE _ph20_21_salute_bindings(id TEXT PRIMARY KEY);
INSERT INTO _ph20_21_salute_bindings SELECT id FROM hub_entity_bindings WHERE module_id='salute';

CREATE TEMP TABLE _ph20_21_salute_contexts(id TEXT PRIMARY KEY);
INSERT OR IGNORE INTO _ph20_21_salute_contexts
SELECT DISTINCT context_id FROM hub_context_members WHERE entity_id IN (SELECT id FROM _ph20_21_salute_bindings);

CREATE TEMP TABLE _ph20_21_salute_tags(id TEXT PRIMARY KEY);
INSERT INTO _ph20_21_salute_tags
SELECT id FROM hub_tags WHERE namespace='salute' OR namespace LIKE 'salute.%';

DELETE FROM hub_context_members WHERE entity_id IN (SELECT id FROM _ph20_21_salute_bindings);
DELETE FROM hub_tag_assignments WHERE target_binding_id IN (SELECT id FROM _ph20_21_salute_bindings);
DELETE FROM hub_entity_bindings WHERE id IN (SELECT id FROM _ph20_21_salute_bindings);
DELETE FROM hub_contexts
WHERE id IN (SELECT id FROM _ph20_21_salute_contexts)
  AND NOT EXISTS (SELECT 1 FROM hub_context_members WHERE hub_context_members.context_id=hub_contexts.id);

DELETE FROM hub_tag_aliases WHERE tag_id IN (SELECT id FROM _ph20_21_salute_tags);
DELETE FROM hub_tag_assignments WHERE tag_id IN (SELECT id FROM _ph20_21_salute_tags);
DELETE FROM hub_tag_parents
WHERE child_tag_id IN (SELECT id FROM _ph20_21_salute_tags)
   OR parent_tag_id IN (SELECT id FROM _ph20_21_salute_tags);
DELETE FROM hub_tags WHERE id IN (SELECT id FROM _ph20_21_salute_tags);
DELETE FROM hub_saved_tag_filters WHERE namespace='salute' OR namespace LIKE 'salute.%';
DELETE FROM hub_context_type_fields WHERE accepted_module_id='salute';
DELETE FROM hub_activity_log WHERE module_id='salute' OR source_table LIKE 'health_%';
DELETE FROM hub_sync_pending WHERE table_name LIKE 'health_%';
DELETE FROM hub_sync_known WHERE table_name LIKE 'health_%';

DROP VIEW IF EXISTS v_health_measurement_turnaround;
DROP VIEW IF EXISTS v_health_sample_turnaround;
DROP VIEW IF EXISTS v_health_turnaround_stats;
DROP VIEW IF EXISTS v_health_samples;
DROP VIEW IF EXISTS v_health_measurements;
DROP VIEW IF EXISTS v_health_sample_measurements;
DROP VIEW IF EXISTS v_health_journal;
DROP VIEW IF EXISTS v_health_ai_evidence;
DROP VIEW IF EXISTS v_health_timeline;

DROP TABLE IF EXISTS health_ai_evidence;
DROP TABLE IF EXISTS health_ai_snapshots;
DROP TABLE IF EXISTS health_journal_entries;
DROP TABLE IF EXISTS health_measurements;
DROP TABLE IF EXISTS health_examinations;
DROP TABLE IF EXISTS health_samples;
DROP TABLE IF EXISTS health_events;
DROP TABLE IF EXISTS health_source_metadata;
DROP TABLE IF EXISTS health_import_batches;

UPDATE hub_generation SET generation=generation+1 WHERE id=1;
CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT);
INSERT OR REPLACE INTO room_master_table(id,identity_hash)
VALUES(42,'7b21745c7c548b03282ae48e9542b756');
PRAGMA user_version=21;

DROP TABLE _ph20_21_salute_bindings;
DROP TABLE _ph20_21_salute_contexts;
DROP TABLE _ph20_21_salute_tags;
COMMIT;
PRAGMA foreign_keys=ON;
