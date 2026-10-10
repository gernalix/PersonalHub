# 925611 — B6 Since When acceptance

Local verification 2026-10-10, canonical Pixel_8a API 36, live serial emulator-5554.
Non-distributable isolated `com.gernalix.personalhub.qa`; no Pixel, final APK, NV or real user DB changes.

## PASS
- com.gernalix.personalhub.sincewhen.SinceWhenEditorTest: 3 tests, 0 failures, 0 errors.
- com.gernalix.personalhub.core.database.SinceWhenCurrentSchemaTest: 2 tests, 0 failures, 0 errors.
- com.gernalix.personalhub.qa QA SHA256: `f4df8def93c52cfad9b447adcb7b21ea8885d05ba9aee1c93655c90af0a272b5`.
- com.gernalix.personalhub.qa.test QA SHA256: `28832e71f249b634057b72354dfb5b6376255f0e4418d78ef8ae06b6b7164ea9`.
- `:app:compileDebugKotlin` and `checkArchitectureBoundaries`: PASS.
- New EN/IT resources owned by the existing bilingual core/ui catalog: app lintPlay and core/ui lintDebug PASS; Italian packaged-resource assertions PASS.
- Real production Activity UI: create → save → recreate → edit → readback, 09:17 → 13:47, optional end 23:52 → none, blue, HOURS, independent Since When tag. Timer namespace tag absent.
- Legacy/source snapshot: title-only edit preserves exact start/end milliseconds, description, custom color, units, legacy tags, source_entity_type/id/timestamp_field and created_at; Activity recreation readback PASS.
- Local/UTC conversion tests cover unchanged sub-minute precision, date/time edit across seasonal offsets and the DST overlap.
- Screenshot of the reopened legacy editor: `editor.png`.
- Existing canonical DAO/schema and legacy migration seam retained. Editor imports no Timer code and creates no parallel counter engine.

## Commands
```
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests 'com.gernalix.personalhub.sincewhen.SinceWhenEditorTest' :core:database:testDebugUnitTest --tests 'com.gernalix.personalhub.core.database.SinceWhenCurrentSchemaTest' checkArchitectureBoundaries --quiet --console=plain
./gradlew :app:assembleQa :app:assembleQaAndroidTest -Ppersonalhub.testBuildType=qa --quiet --console=plain
python3 tools/android_connected_test_gate.py -- <adb> -s <live serial> shell am instrument -w -r -e class com.gernalix.personalhub.SinceWhenEditorRoundTripDeviceTest com.gernalix.personalhub.qa.test/androidx.test.runner.AndroidJUnitRunner
```
The exact artifacts from Gradle output-metadata.json were installed. The instrumentation receipt is `device-roundtrip.txt`: OK (1 test), status 0, fail-closed wrapper exit 0.

## Resolved obstacles
- Kotlin cross-module nullable-property smart cast: local value, no API change.
- QA selection disables debug unit tasks: host tests and QA build run separately.
- Instrumentation process lacked Hub runtime bootstrap: QA fixture opens its current database and initializes the existing production adapter composition, without deleting existing QA data. Corrected test APK rebuilt and installed; app artifact unchanged.

- CI revealed that a new app Italian catalog requires translation of all 318 untranslated app resources. Moved only the editor vocabulary into the existing bilingual core/ui catalog, using unique editor keys. No lint suppression or unrelated translation work. App/core-ui lint PASS and final changed app QA round trip PASS (11.698 s).
- Repeated legacy fixture initially reused a unique source snapshot key from the prior test. Added the run suffix to its synthetic source ID; no QA DB clearing or user data change. Final test receipt supersedes the earlier checkpoint.

## PASS correction: Timer independence

The initial editor PASS did not prove full autonomy: the list UI, source-create UI and Hub adapter still called TimerStartupApi.ensureLegacySinceWhenMigrated. Those calls and readiness/error UI gates are now removed. The editor itself is unchanged.

One migration implementation remains, in the separate LegacySinceWhenMigrationApi compatibility boundary, with the same existing transaction, marker key and canonical field mapping. It runs in the existing host post-first-frame maintenance worker, in its own failure-isolated step before the Timer-only first-screen latch. No new scheduler, schema or migration marker. Failure does not gate canonical Since When operations; the existing once-per-process host maintenance retries on next process startup while the marker is incomplete.

Canonical counters created before import may occupy a legacy Timer ID. The importer preserves those canonical rows and allocates a free existing database ID for only the colliding imported row. Otherwise legacy IDs are retained. Completion is marked atomically with the copy, so repeated/concurrent calls cannot duplicate imports.

Verification:
- 3 new migration host tests PASS: exact field copy, concurrent/repeated calls, collision preserving both rows, malformed snapshot leaves marker incomplete and allows successful retry.
- compileDebugKotlin and architecture boundaries PASS; removed-API consumer gate PASS.
- Real canonical Pixel_8a isolated QA: production SinceWhenActivity create/save/reopen/edit, Hub exists/search/summary/open, and source creation through the production deep-link Activity all PASS while legacy import is pending and actually throws on a corrupt snapshot. Runtime fixture registers only the Since When adapter, with no Timer startup initialization. Snapshot and completion-marker fixtures are restored in finally; no live user DB touched.
- Receipt autonomy-device.txt: OK (1 test), fail-closed wrapper exit 0. The regression is included in the existing emulator CI selection.
- Reused the prior 5 editor/persistence/time tests and complete editor round trip: the editor/model/resources are unchanged.
- Post-merge CI for initial main 05184af0: all 4 workflows completed/success (38043943079 architecture, 38043943040 unit, 38043942934 Play, 38043943042 instrumentation); no unrelated CI repair.

Corrected QA artifact fingerprints:
- com.gernalix.personalhub.qa: `40e7fabb4e9fed5143ff8da448833c64c88804dbfa403f8ee503b73f1fd02688`.
- com.gernalix.personalhub.qa.test: `fafcd3e3f7da0e01ac640edc80abb14640784597211cc91ad44ecf422e1c15f7`.
