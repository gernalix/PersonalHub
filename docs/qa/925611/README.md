# 925611 — B6 Since When acceptance

Local verification 2026-10-10, canonical Pixel_8a API 36, live serial emulator-5554.
Non-distributable isolated `com.gernalix.personalhub.qa`; no Pixel, final APK, NV or real user DB changes.

## PASS
- com.gernalix.personalhub.sincewhen.SinceWhenEditorTest: 3 tests, 0 failures, 0 errors.
- com.gernalix.personalhub.core.database.SinceWhenCurrentSchemaTest: 2 tests, 0 failures, 0 errors.
- com.gernalix.personalhub.qa QA SHA256: `e57e653bab8e12c9eaaaf35bb47b8acc7a37106043b18cfefa88392941b54f55`.
- com.gernalix.personalhub.qa.test QA SHA256: `c0b51d5f58572126204d73e59d5d9f5dc8bc816cb5d8857f5217fe71daff84e5`.
- `:app:compileDebugKotlin` and `checkArchitectureBoundaries`: PASS.
- New EN/IT resources: XML parse PASS.
- Real production Activity UI: create → save → recreate → edit → readback, 09:17 → 13:47, optional end 23:52 → none, blue, HOURS, independent Since When tag. Timer namespace tag absent.
- Legacy/source snapshot: title-only edit preserves exact start/end milliseconds, description, custom color, units, legacy tags, source_entity_type/id/timestamp_field and created_at; Activity recreation readback PASS.
- Local/UTC conversion tests cover unchanged sub-minute precision, date/time edit across seasonal offsets and the DST overlap.
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
