# PersonalHub architecture

PersonalHub contains People, Timer, Places, Substances and WordPulse. The application package is `com.gernalix.personalhub`.

## Database capsule

`:core:database` owns the single canonical data file `personalhub.db`. `PersonalHubDatabase` is the only Room schema owner and declares all 55 entities and all feature DAOs. Entity/DAO packages remain stable for source compatibility. Feature database names are type aliases to this owner, not independent Room builders. The timer compatibility adapter borrows the same Room connection; it does not create, version or close another canonical database.

Original identifiers, relationships, epoch timestamps, UTC strings, timer snapshots/history, configuration rows, and the remote-sync outbox are retained. WordPulse's session table is `wordpulse_sessions` to avoid collision with timer `sessions`. WorkManager's own scheduling database is operational metadata, not a feature data store.

`people_photos` stores one BLOB per contact photo field, with foreign keys to the person and field, the original reference, MIME type, SHA-256, and timestamp. Cropped photos are transient until attached in the same transaction as their contact. The original reference is provenance and a lookup key; display reads the BLOB. Missing original files can be explicitly accepted by the offline builder without generating replacement images.

## File import and automatic export

The app shell exposes **Database and backup**, **Import personalhub.db**, and a SAF folder picker. Feature backup commands delegate to this capsule. Legacy module databases are moved to a private recovery directory after a successful import; they are no longer opened or used as sources.

Import copies the selection to staging and verifies the SQLite header/version, complete table/column/PK/FK schema, required indexes, allowed dirty triggers, quick check, foreign key check and photo SHA-256. The writer gate drains active transactions, checkpoints WAL, saves a durable pre-import copy, closes Room, replaces the file by atomic rename, clears old sidecars and verifies reopening. A journal permits automatic rollback after interruption. A separate foreground relay process restarts the application graph after success. Old writers remain suspended until that restart.

Persistent SQLite triggers increment `hub_generation` inside the modifying transaction. Rollbacks do not advance it. This covers every Room DAO and timer compatibility write. A short process-local check schedules a debounced WorkManager job; periodic recovery and application startup recover unexported commits after process death. Operations serialize. Export excludes writers while checkpointing and taking a standalone snapshot. The SAF temporary file is read back and checked byte-for-byte before publication; the last good export is retained as `personalhub.db.bak`. Failed exports remain dirty and retry. Export status is stored outside the data DB to avoid a dirty/export loop. Android force-stop and revoked/inaccessible SAF access necessarily delay recovery until the application can run and the folder is available again.

## Building the real database offline

Use `tools/build_personalhub_db.py --help`. Inputs must be coherent original-app exports or SQLite online-backup snapshots, including all relevant WAL changes. Never pass an unchecked live file. The target schema comes directly from Room's checked-in schema JSON; the same JSON is packaged as an application asset for validation.

Required arguments: `--people`, `--timer`, `--timer-sync`, `--places`, `--substances`, `--wordpulse`, `--photos-dir`, `--output`. `--people-preferences` accepts the original supercontacts_home.xml read-only extraction. Timer user settings use the existing ui_prefs_mirror row directly; people home preferences use hub_preferences. Both are canonical rows and trigger autoexport. `--allow-missing-photos` is an explicit exception for unavailable original files. Existing outputs are never overwritten. No domain seed or fixture rows are generated. The companion validation JSON records source hashes, per-table schemas/counts/latest timestamps, exact source-to-target row comparisons, photograph results, target integrity, size and hash. Personal data and recovered files must stay outside Git.

## Validation and remaining boundaries

Android QA uses the installed `android-emulator-qa` skill with ADB UI hierarchy, screenshots and logcat. `GlobalDatabaseInstrumentedTest` runs only on an emulator with the isolated `PersonalHubQA` SAF folder. It tests committed add/edit/delete for all five modules against SAF readback, concurrent preference writers, unchanged-setting deduplication, rollback generation behavior, multiple new photo BLOBs and cascade deletion, rejected invalid imports, and full-table rollback after a Room reopening failure. The fifteen module writes are device instrumentation against the canonical database, not fifteen UI automation scenarios. UI import, module display, restart and export/reimport are separate device gates.

The existing feature interfaces and business logic are retained behind their capsules. Cross-feature place lookup remains an internal content provider. The timer's snapshot JSON and relational projections preserve the original semantics; these are tables in the one schema, not extra databases. Pre-existing feature localization and presentation code remain progressively encapsulated. New transfer UI uses English and Italian resources.

## Verified recovery 731684

The final acquisition cutoff was 2026-09-03T03:20:44Z. Native exports were used for People, Timer, Places and Substances; coherent read-only `run-as` snapshots supplied WordPulse and the timer sync queue. Substances and WordPulse were refreshed when their original data changed during QA. Native sources with accessible sandboxes were compared with coherent live snapshots. Luoghi was not debuggable, but its complete readable native export matched its manifest hash and size.

Copied source rows: People 2,753; Timer 3,631 plus 1,119 sync rows; Places 1,068; Substances 2,745; WordPulse 24,208. Every source row value, primary key and timestamp was compared after conversion. Photo references: 40; BLOBs recovered and hash-verified: 37; three unresolved references were explicitly waived by the user. Two People home preference keys were recovered separately. The builder was run twice with identical output hashes. No private data or screenshots are checked into this repository.

API 36 Pixel 8a emulator QA covered native SAF import and automatic foreground restart, all five module screens, real contact photographs, all committed module mutation types with autoexport readback, corrupt/incompatible inputs, post-replacement failure rollback, concurrent writes, and full database equivalence through export/reimport. The final source and round-trip export matched across all domain and generation tables and SQLite sequence values. Original Pixel applications remained read-only. See the generated private validation report for the final artifact path, per-table evidence and source hashes.
