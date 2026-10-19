# Debug lint inventory

Final72 optimized configuration app debug lint0 and architecture PASS456. AGP debug+minify warnings from the rejected first candidate are addressed by disabling debug mode only for the final optimization opt-in; QA explicitly remains debuggable/unminified. Full replacement APK proof is pending. Original Configuration.setVisible upstream deprecation remains.

Additional cross-module cursor repair: app compile/QA build and architecture PASS; fresh core/ui, Notifications, Soldi debug lint and app QA lint each0 findings. Seven core/ui host cases and23 native cases PASS441–448 (445 startup test failure retained, resolved446). The official AGP deprecation remains separate and unchanged.

Last verified full lint snapshot on the uncommitted modernization:14 complete module reports,0 issues, architecture PASS (SQLite413). The full host run has375 PASS/1 existing AndroidKeyStore SKIP, and subsequent focused host fixtures36 PASS. Actual nonincremental compilation and NDK strip/package validation now have0 Kotlin/native-strip warnings. One Gradle deprecation is emitted by26 internal calls in the official AGP9.4.1 artifact; its source provenance is retained privately and no warning filter was added. The later Places search-draft correction also passes app compile, QA build, Luoghi lint0 and architecture. Native delayed-state regression, four search repetitions and full app16 cases PASS433–438.



The full `lintDebug checkArchitectureBoundaries` run on19 October completed successfully (no lint errors, architecture PASS). It exposed393 distinct warnings/hints across the whole project; this is the original historical baseline, not the current residual count. Raw source-backed findings are retained privately in `~/.local/share/personalhub-qa/lint-20261019.json` (0600). This snapshot is taken before the following fixes are rechecked.

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

## Places compatibility correction in progress

The official6.0.2 AAR was inspected directly, including public Java signatures. `fetchPlace(FetchPlaceRequest)` and `initializeWithNewPlacesApiEnabled` remain present. The removed model members used by PH are the legacy ADDRESS/LAT_LNG/NAME fields and corresponding getters. The adapter now requests FORMATTED_ADDRESS/LOCATION/DISPLAY_NAME and reads formattedAddress/location/displayName, retaining the same session token, cancellation, coordinate validation and fallback text. The obsolete deprecation suppression is removed. The version catalog now selects6.0.2. This corrects the earlier broad interpretation of the release notes above; no Cloud setting, API key, permission or billing configuration was changed.

The removed-literal consumer gate passes. App compilation is running once with one low-priority worker; this section does not claim its result or a refreshed full-lint count. The previous frozen70 artifact and its failed/passing evidence remain untouched. Because application inputs change, this dependency correction requires a new immutable candidate and repeated final QA before delivery, using the existing70 delivery version.

Sources: [Places release notes](https://developers.google.com/maps/documentation/places/android-sdk/release-notes), [official6.0.2 artifact](https://dl.google.com/dl/android/maven2/com/google/android/libraries/places/places/6.0.2/places-6.0.2.aar). The private artifact/API inspection provenance is under `~/.local/share/personalhub-qa/dependency-review-20261019/`.

The first compile against Places6 correctly failed on its Kotlin2.4 metadata while PH used Kotlin2.2.21. Kotlin and Compose compiler plugins are now aligned to2.4.21, within the current AGP9.4 toolchain support. The next compile exposed four stricter inferred-intersection errors in mixed SQL binding arrays. Those arrays now explicitly use Any/Any? as required by the existing SQLite API; SQL text, order, values, null handling and transactions are unchanged. No metadata-skip flag or compiler-warning suppression was added. The failed compilation logs remain private evidence; the corrected highest-consumer compile is running.

Compatibility sources: [Kotlin2.4 compatibility guide](https://kotlinlang.org/docs/compatibility-guide-24.html), [Kotlin releases](https://kotlinlang.org/docs/releases.html), [AGP Kotlin support](https://developer.android.com/build/kotlin-support). Seven proposed stable AndroidX AARs were also downloaded from Google Maven and their actual minimum compileSdk/AGP metadata checked: all fit the existing compileSdk37/AGP9.4.1 matrix. Their upgrades have not yet been applied; those metadata checks are prerequisites, not functional acceptance.

## Version-matrix validation in progress

The Places6/Kotlin2.4 application compile passed after12 explicit SQL binding-array type arguments in six production owners; comparison against the previous source confirms that all other statements in those six files are byte-identical. Two existing host fixtures needed the same explicit array element type. All28 selected host cases and architecture PASS (SQLite89–93), with immutable input hashes and JUnit evidence. These results precede the remaining catalog updates below.

The remaining stable dependency suggestions are now applied to the catalog. All modules already compile against37; minimum runtime requirements checked for the AndroidX updates fit PH’s existing minSdk26/29 modules. Coil3.6.3 requires Kotlin2.4.10/Compose1.12 and includes AGP9.4/R8 fixes, so its update is paired with Kotlin2.4.21 and the new Compose BOM. Robolectric4.17 removed API consumer gate passes. Gradle wrapper regeneration is in progress at9.8.1 with the official distribution checksum. No pre-release coordinates, warning baselines or metadata-skip options are used. Full compilation/test/lint acceptance of this combined matrix remains pending.

| Version key | Previous | Selected |
| --- | --- | --- |
| `kotlin` | `2.2.21` | `2.4.21` |
| `activityCompose` | `1.12.0` | `1.13.0` |
| `benchmark` | `1.4.1` | `1.5.0` |
| `composeBom` | `2026.06.01` | `2026.09.00` |
| `coreKtx` | `1.17.0` | `1.19.1` |
| `coroutines` | `1.10.2` | `1.11.0` |
| `coil` | `3.4.0` | `3.6.3` |
| `errorProneAnnotations` | `2.42.0` | `2.50.0` |
| `lifecycle` | `2.10.0` | `2.11.0` |
| `onnxRuntime` | `1.30.0` | `1.31.0` |
| `places` | `4.4.1` | `6.0.2` |
| `playServicesLocation` | `21.3.0` | `21.4.0` |
| `robolectric` | `4.16` | `4.17` |
| `room` | `2.8.4` | `2.8.5` |
| `work` | `2.11.2` | `2.12.0` |
| `uiautomator` | `2.3.0` | `2.4.0` |
| Gradle wrapper | `9.6.0` | `9.8.1`, official SHA256 |

Primary compatibility notes: [WorkManager](https://developer.android.com/jetpack/androidx/releases/work), [Room](https://developer.android.com/jetpack/androidx/releases/room), [Activity](https://developer.android.com/jetpack/androidx/releases/activity), [Coil](https://coil-kt.github.io/coil/changelog/), [Coroutines1.11](https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.11.0), [Robolectric4.17](https://github.com/robolectric/robolectric/releases/tag/robolectric-4.17), [ONNX1.31](https://github.com/microsoft/onnxruntime/releases/tag/v1.31.0), [Gradle checksum](https://services.gradle.org/distributions/gradle-9.8.1-bin.zip.sha256).

## Combined-matrix test-runtime correction

The combined application compile passed with all selected version updates and the regenerated Gradle9.8.1 wrapper. The first full gate produced293 actual cases before stopping:10 failures in three Timer test classes occurred during Robolectric setup, before test bodies, because Java did not export `jdk.internal.access`. Every completed report is preserved (SQLite94–172), including those failures. Added only the required export to Gradle Test JVMs, with no app/runtime flag, lower test SDK or skipped assertion. All10 previously failing cases now PASS (SQLite173–175). The necessary full unit/lint/architecture/Android-test compilation gate is running again; no full-lint/whole-gate PASS is inferred from these focused results. [Robolectric JVM setup guidance](https://robolectric.org/getting-started/#running-with-java-17-and-higher).

Valid CRLF in the canonically generated Windows launcher is now declared through a path-specific Git whitespace attribute. Standard whitespace checks remain active and `git diff --check` passes; no wrapper content or global Git policy was changed to hide the line endings.

## Full matrix after JDK repair

The detached full command completed with exit1 after15m54s. All79 JUnit reports/298 real cases PASS (SQLite203–281), architecture PASS and full lint reports one warning with no errors. Android instrumentation compilation then failed on seven mixed SQL arrays in five existing app fixture files. Explicit `arrayOf<Any>` types now preserve each original statement, binding and assertion. No device guard or coverage level was weakened. Combined command failure is retained separately as SQLite282; it is not hidden by the passing unit results.

The last lint warning came from the new Lifecycle contract marking the empty ViewModel `super.onCleared` implementation. Removed only that call, keeping the existing PVT job cancellation. Highest app compile passes after this and the two widget inset fixes. Targeted widget host suites, the Android test compilation and full unfiltered lint are in progress.

Post-repair gates PASS: Android instrumentation source compilation, targeted widget suites7/7 cases (SQLite285–286), architecture and full unfiltered lint14 reports/0 errors/0 warnings. SQLite287 records three actual Gradle task gates, explicitly not individual unit cases. The previous298-case full unit run remains separate. Private complete XML/hash manifest: evidence/post-matrix-leaf-lint/manifest.json.

## Compiler warnings are a separate acceptance gate

The fa9b4eb3 CI log is preserved privately and reconciled into compiler-warnings-fa9b4eb3.json (75 unique locations/messages, including3 Gradle-script deprecations). A quiet compile can pass while hiding these messages. The next warning acceptance uses visible warnings and a comprehensive necessary compilation; no global suppression or weaker test/data guard is introduced. Library Timer's actual nullable-array error is repaired explicitly; all three requested library Android-test compilation tasks pass before the later source modernizations.
