# PersonalHub Architecture

PersonalHub is the unified Android app for the five in-scope source apps:

- SuperContacts -> People
- MultiTimeTracker -> Timer
- Luoghi -> Places
- Sostanze -> Substances
- WordPulse -> WordPulse

## Package And Modules

- Installed application package: `com.gernalix.personalhub`
- App shell: `:app`
- Shared model and migration contract: `:core:model`, `:core:migration`
- Feature modules: `:feature:supercontacts`, `:feature:multitimetracker`, `:feature:luoghi`, `:feature:sostanze`, `:feature:wordpulse`

Each source app is compiled into the single APK as a library module. The original packages are preserved inside each module to keep existing data access, UI behavior, resources, tests, and backup validation semantics close to the source implementation.

## Data Isolation

The first unified version keeps each feature's database isolated inside the single PersonalHub sandbox:

- Luoghi: `luoghi.db`
- MultiTimeTracker: `multitimer.db` and `mtt_remote_sync.db`
- Sostanze: `sostanze.db`
- SuperContacts: `super_contacts.db`
- WordPulse: `wordpulse.db`
- Migration map: `personalhub_migration_map.db`

This preserves sensitive feature boundaries and avoids merging tables merely because they have similar names. The only shared contract added here is a deterministic mapping layer that records source app, source table, source id, target entity type, target id, and dedupe decision.

## Migration Mapping

The mapping rule is:

`old_app:old_table:old_id -> new_entity:new_id`

Initial imports use a namespaced ID such as `supercontacts:contacts:42`. This prevents collisions between unrelated tables that share integer IDs, for example MultiTimeTracker `sessions:1` and WordPulse `sessions:1`.

Deduplication is only allowed when identity is proven. Ambiguous records remain distinct and can later be linked by an explicit relationship instead of merged destructively.

The implemented table inventory lives in `MigrationInventory` under `:core:model` and covers all audited source tables for the five source apps above.

`MigrationVerifier` turns scanned source-row IDs into deterministic mappings and rejects incomplete batches before import can be considered safe:

- source rows without a mapping;
- mappings without a source row;
- duplicate mappings for the same source row;
- duplicate `new_id` target collisions.

`MigrationMappingStore.recordAll` persists a verified batch in one SQLite transaction, so a duplicate source key rolls back the whole batch instead of leaving a partial migration map.

`MigrationSourceDatabaseScanner` is the Android SQLite bridge for this flow. It reads configured source ID columns from one source database, including attached-database table names such as `mtt_remote_sync.sync_queue`, and returns `SourceTableRows` for the verifier.

## Cross-Feature Links

The Luoghi provider authority is internal to PersonalHub:

`com.gernalix.personalhub.luoghi.places`

SuperContacts resolves place suggestions through that internal provider. This keeps the cross-feature reference inside the single APK and avoids provider-authority conflicts with the old standalone Luoghi app if it is still installed during migration.

## Backup And Restore

The original backup/import paths are preserved inside their feature modules:

- Luoghi keeps its SAF database plus manifest flow.
- MultiTimeTracker keeps its SQLite vault, CSV/JSON ZIP export, rollback, and WorkManager sync structures.
- Sostanze keeps single-DB SAF export/import.
- SuperContacts keeps SQLite backup/import and photo support.
- WordPulse keeps CSV backup/import for sessions, words, and corrections.

Future import UX should write `MigrationMappingStore` rows as records are imported or linked into shared views.
