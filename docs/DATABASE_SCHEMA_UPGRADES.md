# External database schema upgrades

PersonalHub does not ship historical Room migrations inside the APK. A data-bearing device must be moved to the schema required by the APK before database-backed screens run.

## Runtime gate

DatabaseStartupGate runs before the Home UI and before every module Activity initializes feature code. A mismatched SQLite user_version produces a blocking compatibility screen instead of allowing Room-backed code to crash.

DatabaseVault.ensureStartupReady remains the structural validator. The gate is not a migration engine.

## Schema 20 to 21

Version 60 requires schema 21. Schema 21 is schema 20 with the Salute domain removed; the 92 retained Room tables are unchanged.

Use:

- tools/migrations/personalhub_20_21.sql
- tools/migrate_personalhub_v20_to_v21.py
- tools/test_migrate_personalhub_v20_to_v21.py

The runner refuses non-v20 or wrong Room-identity sources, uses SQLite backup so WAL/SHM state is included, never mutates the source, suspends copied runtime triggers, removes Salute data/schema, writes user_version 21 plus the schema-21 Room identity, validates the complete current Room table/foreign-key/index contract, runs quick_check and foreign_key_check, and verifies hashes for every non-Salute table not intentionally touched.

Example:

    python3 tools/migrate_personalhub_v20_to_v21.py personalhub-v20.db --output personalhub-v21.db

Replace the live device database only after the JSON validation report says PASS. Preserve the original database and sidecars until v60 has passed runtime smoke testing.

An APK build/install is not a database migration. Never substitute uninstall, clear-data, destructive Room fallback, or a user_version-only edit.
