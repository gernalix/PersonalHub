# P31 — PersonalHub NV source cutover

PersonalHub keeps its published NV 65. Root `NV.txt` replaces `version.txt`; application and feature BuildConfig values, manifest versionCode/versionName, CI and distribution gate read the same source. Home displays `BuildConfig.NV` as the discreet bottom-right number.

Verification on 2026-10-11: compileDebugKotlin and assembleDebug passed; seven APK-version tests passed. The packaged QA APK was verified as com.gernalix.personalhub, versionCode 65 and versionName 65. The canonical Pixel_8a emulator showed Home “PersonalHub” and “65” at bounds [1003,2253][1038,2295]. Installation used `adb install -r`, preserving application data.

The emulator's existing schema-23 database required the checked-in external schema-24 migrator before runtime QA. Its report passed integrity and foreign-key checks; the original database and WAL were retained in a private host backup and a device rollback directory. No source changes to migration behavior, no uninstall or clear-data, and no physical Pixel interaction occurred.

This is a source migration with a QA-only APK, not a new final distributable; NV remains 65 and no APK is sent externally. MegaVault adoption verifies the published consumers and records the same number through its existing canonical version writer.
