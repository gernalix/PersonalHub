# Debug lint inventory

The full `lintDebug checkArchitectureBoundaries` run on19 October completed successfully (no lint errors, architecture PASS). It exposed393 distinct warnings/hints across the whole project; these remain an explicit open goal item, not a clean-lint claim. Raw source-backed findings are retained privately in `~/.local/share/personalhub-qa/lint-20261019.json` (0600). This snapshot is taken before the following fixes are rechecked.

Priority fixes in progress: widget configuration replies no longer return the incoming external Intent; notification restoration rejects actions outside its four manifest system actions; asynchronous benchmark tab transitions use paired async trace sections with disposal cleanup; composition reads LocalResources; the Timer hub adapter uses its own existing resource directly, and the redundant private Material resource override is removed.

Existing synchronous preference commits and application-context retention require semantic review, not a mechanical conversion to asynchronous writes or weaker durability. New-version suggestions need compatibility assessment against the pinned release matrix; no blind dependency upgrade or blanket lint disable has been applied. Unused-resource findings need consumer/reflective-name validation before removal.

| Lint diagnostic | Distinct findings in snapshot |
| --- | ---: |
| `AndroidGradlePluginVersion` | 1 |
| `ApplySharedPref` | 8 |
| `AutoboxingStateCreation` | 28 |
| `DiscouragedApi` | 1 |
| `GradleDependency` | 16 |
| `HardcodedText` | 2 |
| `IconDuplicates` | 5 |
| `IconLauncherShape` | 10 |
| `InlinedApi` | 2 |
| `LocalContextResourcesRead` | 2 |
| `ModifierParameter` | 6 |
| `NewerVersionAvailable` | 9 |
| `ObsoleteSdkInt` | 9 |
| `PluralsCandidate` | 42 |
| `PrivateResource` | 1 |
| `QueryPermissionsNeeded` | 2 |
| `SetTextI18n` | 2 |
| `StaticFieldLeak` | 4 |
| `TypographyEllipsis` | 2 |
| `UnclosedTrace` | 1 |
| `UnsafeIntentLaunch` | 2 |
| `UnsafeProtectedBroadcastReceiver` | 1 |
| `Untranslatable` | 3 |
| `UnusedAttribute` | 8 |
| `UnusedResources` | 98 |
| `UseKtx` | 127 |
| `UseTomlInstead` | 1 |

## Validated cleanup checkpoint

The update notice's3 host cases and both widget suites (4+3+3 cases) pass, stored in SQLite47–50. The test-owned manifest fixes the reduced Robolectric package; production initialization is unchanged. Focused lint reports0 findings in core/ui and Places.

Completed source fixes additionally include48 toUri recommendations,28 primitive Compose state creations and98 unused default app strings. Consumer scans were read back; broad prefix matches were checked explicitly, and the live core-owned identical resource was retained. Highest affected app compilation passed after each group.

Reviewed false positives now have narrow, justified annotations: process-owned Application context/window retention (DatabaseGate and CallSystemOverlayController) and compile-time integer constants in the pure API36 package-source policy. This does not authorize retaining Activity contexts or calling unsupported platform APIs. Synchronous durability-critical preference writes remain unchanged.

The next full lint/architecture run is in progress. Remaining categories are still open until their refreshed findings and necessary validation are reviewed; this checkpoint does not claim all393 diagnostics have been resolved.

## Reconciliation after cleanup

Full lint and architecture completed successfully again. Distinct diagnostics dropped from393 to195 (198 removed/resolved); the original393 comprised365 warnings and28 performance hints, with0 errors/fatals. The current195 are warnings. Counts use the diagnostic ID, source path, line and message; neither snapshot contains duplicate keys. Current report reconciliation found0 duplicate report entries and0 fatal/error findings. All179 text findings with a source snippet and line matched the current source exactly; the remaining file-level findings had no comparable text line. No mismatched source snippet was found. This verifies the count's provenance; it does not convert remaining warnings into accepted defects or claim a finished release.

Remaining diagnostic categories:37 plural/count wording,79 AndroidX style recommendations,26 dependency/toolchain update suggestions,9 redundant SDK branches,8 newer widget/manifest attributes,6 Modifier ordering,15 legacy icon findings,8 synchronous preference recommendations,2 hardcoded widget labels,2 external-app visibility queries,2 ellipsis typography and1 version-catalog recommendation. Prioritize concrete behavioral findings; preserve checked synchronous commits and evaluate intentional compatibility/pinning explicitly.

## Full unfiltered checkpoint at 7d53da4d

`lintDebug checkArchitectureBoundaries` completed successfully with113 distinct warnings and0 errors:79 UseKtx,8 ApplySharedPref and26 dependency/toolchain availability suggestions. The source-backed snapshot is private `~/.local/share/personalhub-qa/lint-7d53da4d-20261019.json`. Compared with393 initially,280 diagnostics no longer occur. This is an intermediate source check, not final runtime or release acceptance.

The remaining preference recommendations were checked against persistence semantics. AndroidX `SharedPreferences.edit(commit=true)` returns Unit; it cannot replace a checked `commit(): Boolean` without losing failure detection. Profile handoffs, observed-version tracking, the database-open manifest, receipt consent/aliases and import/export recovery retain their synchronous writes. Their source annotations explain this intentional API choice. Explicit restore/immutable-archive transaction boundaries are also retained with local explanations. Ordinary asynchronous preferences are being converted separately and still require compilation/review after automatic edits.

One automatic lint patch was rejected before compilation or installation because it discarded checked commit results and damaged editor blocks. Its exact diff is preserved privately. A subsequent run excludes these intentional low-level operations; no project-wide lint disable, baseline or permanent check filter is used.

The26 available-version messages remain visible pending compatibility assessment. In particular, [Places6 release notes](https://developers.google.com/maps/documentation/places/android-sdk/release-notes) explicitly remove APIs used by the current location engine; [AGP9.4 compatibility](https://developer.android.com/build/releases/agp-9-4-0-release-notes) lists the existing Gradle9.6.0 toolchain. A newer version is not, on its own, proof of a runtime defect or authorization to change Cloud billing/API enablement.

## Reviewed AndroidX validation

The highest app compile and the full unfiltered lint/architecture command both completed with exit0. All21 focused host cases pass:4 profile/recovery,1 database-open manifest,3 update-tracker,7 Timer widget,3 Substances widget and3 receipt-import cases. Immutable hash-verified private JUnit evidence feeds SQLite56–62; no failed historical row was changed.

Full lint now reports26 distinct warnings,0 duplicate entries and0 errors/fatals, all available-version suggestions (1 wrapper,16 Android dependency and9 external version messages). Core/database and the other corrected source owners no longer report the reviewed recommendations. Snapshot: `~/.local/share/personalhub-qa/lint-reviewed-ktx-20261019.json`; full XML reports and hashes are retained privately in `evidence/lint-reviewed-ktx/`. The87 recommendations at the preceding113 checkpoint were either modernized with preserved behavior or explicitly justified where low-level checked/durable APIs are necessary. This distinction matters: the lower count is not a claim that87 runtime bugs existed or that the final APK has passed device acceptance.

The exact installed AndroidX1.17.0 sources were inspected: edit defaults to asynchronous apply and discards commit's Boolean; scale defaults to filtering=true; createBitmap defaults to ARGB_8888; withRotation restores the saved canvas in finally. The accepted conversions preserve those parameters and editor batches. Seven critical files were verified to contain only annotation/import/explanation additions, with every existing persistence/recovery statement unchanged.
