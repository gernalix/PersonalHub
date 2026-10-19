# PersonalHub completion — change inventory

Status: implementation and QA in progress. This is not a final release or purification receipt.

## Product behavior

- Places live search reuses HubSearchField. Case/accent-insensitive AND terms match names, addresses, notes and assigned tag names. Tag selection intersects search and retains sorting.
- Overlapping check-in candidates always require explicit selection, including candidates beyond the former short list. No automatic nearest choice. Cancellation preserves event counts and clears the pending choice. Ambiguity no longer opens the new-place editor.
- Where was I moved from individual Visits to Places Home. Incomplete, overlapping and future visits are handled explicitly. Current active visits use an actual observation time.
- Places timestamps display seconds and a four-digit year, accept older minute/two-digit-year input, reject invalid dates and DST gaps, and preserve the original milliseconds/DST offset when only notes change.
- Places tag writes are transactional. Existing local/global identities survive replacement. Rename, archive/restore, bulk assignment/removal, unused deletion, filtering and rollback have dedicated checks.
- The four Places sorting criteria have explanatory text; direction, stable ties and missing values have regression checks.
- Embedded osmdroid rendering/clustering dependencies were removed. MapViewerActivity now delegates to optional external OsmAnd through the official V2 AIDL protocol.
- The pinned, unmodified API JAR includes matching sources, upstream license, provenance and hashes. No OsmAnd renderer is embedded.
- The bridge has bounded binding, Android 14 activity-start flags and official Parcelable keep rules. It removes its old layer before republishing, sends points individually without a catalogue cap and clears the temporary layer on return/cancel/dead connections.
- Reference and other points use distinct colors. Official showMapPoint centers the external map with a usable zoom, including cold starts. PH does not write favorites or request GPS through the bridge.
- Missing-app, unavailable-map and permission states have actionable English messages and retry/install/access controls. Older OsmAnd versions use their normal Plugins UI when the newer permission intent is unavailable.
- Explore reuses saved places and address autocomplete, supports a temporary reference, stable geographical distance ordering and a separate missing-coordinate section. Selecting a reference does not write place data. Returning from detail preserves it.
- Countdown reuses the Since When editor/clock/calendar units, uses signed time before/after the target, defaults to a future date, updates only in foreground and retains a separate tag namespace. Existing Since When canonical IDs remain intact.
- Home stores compact/extended layout preference. Compact mode has accessible icon tiles for all 14 actions. Countdown is included.
- ModuleHistoryHost supplies the same global History engine/UI with immutable module scope. It is wired into six domain roots, Notifications, Tags, Context, Settings, Since When and Countdown. Module content state survives tab changes.
- Global History module checkboxes remain open across changes until explicit OK/dismissal.
- Notifications History reads the existing private archive through a bounded feature-owned API, merges keyset pages with core History, opens original event details and protects private rows with a secure window whose previous flag is restored afterward.
- Fixed a real FTS4 multiword/prefix query bug in both archive search and shared History: wildcard inside quoted tokens, portable implicit conjunction. No private archive data is copied into canonical Room/Git.
- Fixed a first-foreground update-notice race exposed by the real minified candidate: the database startup gate recreates the first Activity. Observation now survives Activity replacement and presents only on the current resumed owner; background completion waits for foreground without acknowledging the update. Two deterministic lifecycle regressions and the existing one-fact Audit/History test pass. The corrected exact minified70 candidate passes real primary Pixel first-foreground/OK persistence acceptance; full final feature acceptance remains in progress.
- AppUpdateTracker supplies the previously requested exact update prompt, OK acknowledgement and one idempotent semantic version-update event shared by Audit and History. A fresh install creates a baseline without a fictitious update. Real primary-app update/OK/single-fact acceptance passes on the exact minified70 artifact; final Audit/History UI readback remains pending.
- Addressed lint findings exposed by the full CI path: Explore resolves its unnamed-place resource in composition before click callbacks; the Places visit-state resource has a feature-specific name instead of colliding with Compose's private resource. Count labels work for zero/one/many without incorrect fixed plurals, and three loading labels use proper ellipses.
- URI construction uses the existing AndroidX `toUri` extension in the OsmAnd install action, geofence transition intent and Places content-provider contract. Removed an always-true geofence SDK branch while preserving the same PendingIntent flags on every supported API.
- Kept the production WorkManager initializer removal unchanged. A test-owned manifest removes the Startup provider from the reduced host-test manifest, avoiding an unmatched production removal marker and invalid empty metadata in Robolectric. The first selector-based experiment failed all three tests during manifest parsing; the failure is retained in SQLite46, and the corrected three-test run passes in47.
- New English resource strings/plurals are explicitly non-translatable, resolving CI MissingTranslation without adding Italian text or disabling lint.
- Removed every Italian translation resource introduced by this goal. New UI resources remain English. Unrelated pre-existing translations and the explicitly requested update prompt are preserved.

## Data, tooling and safety

- The CI app instrumentation function now uses the same fail-closed connected-test gate as library tests. A cleanup/runner failure after an apparent OK cannot produce PASS. Three host regressions execute the actual shell function against a fake adb; existing gate17 checks also PASS. Unit CI includes these regressions and the six shortcut-safety cases. No app source, permission, database or final APK change.

- Public-shortcut QA supports `--already-installed`: read-only exact SHA256 matching, no reinstall or added permissions, a fresh private evidence directory and automatic per-module startup history with actual installed version/device/hash. Android timeout no longer passes from an Activity line alone; physical/emulated results use the neutral `ALIASES_TARGET` marker. Six host guard tests PASS (SQLite73); actual final-APK shortcut runtime acceptance remains pending.

- Fixed widget loss in the external paired update procedure: package disable pruned Android widget providers and launcher bindings. Freeze now suspends and force-stops PH while retaining its original enabled state; cleanup removes only an owned suspension, validates readback and refuses pre-disabled or foreign-suspended states. Nineteen paired safety tests, seven installer tests and two migration tests pass (SQLite68–70). No APK change/rebuild is required for this host-tool fix. Native widget restoration/binding preservation remains pending; both attempted Pixel windows closed with verified pre/post notices, with no intake activation.

- Room schema 25 adds a defaulted counter mode; mutation capture and readable History/Git presentation distinguish Countdown, including deleted counters.
- External v24-to-v25 preparation makes a coherent backup, preserves source bytes/original fields/canonical IDs, validates integrity/FKs/modes/Room identity and provides rollback. Only the six obsolete generated counter triggers are retired.
- The existing paired Pixel cutover supports 24-to-25 while preserving its 23-to-24 default, freeze/source-hash checks and paired recovery. Downgrade is allowed explicitly only for restoring the backed-up APK during paired rollback; ordinary installs retain their gate.
- Added a dedicated private SQLite QA history outside app data and Git. It records genuine instrumentation/JUnit/unittest outcomes and meaningful Activity startup/suite-duration samples, including failures/incomplete attempts, exact APK hashes and device/source provenance. Re-import is idempotent; distinct repeated runs remain distinct.
- Suite-only Python reports remain suite-level; no individual cases or retrospective timings are invented. Activity timings are MEASURED rather than functional PASS. Failed startup processes do not retain success samples.
- The connected-test gate writes private evidence safely and records runs automatically. Cleanup false-success and nonzero child status remain failures, including in persisted history.
- Added or extended host/native scenarios for overlaps, search/tags, temporal precision, sorting, Explore, optional/real OsmAnd, Countdown, Home, every module scope, private Notifications History, schema migration, rollback and recorder/gate safety.
- Existing emulator-only guards remain. Shared scenarios have separate strict Google Pixel 8a/.qa/authorization guards; tests use explicitly serial-targeted instrumentation through the gate.
- Pixel QA uses only the isolated package. Mandatory Fedora/Telegram pre/post notices are verified. Ordinary QA now uses the canonical emulator per the latest user steering, with verified shutdown at the end; Pixel is reserved for necessary physical/final-delivery checks. The earlier Pixel-only period remains historical evidence.
- Source consumer closure and app compile gates were applied to changed APIs; architecture gates verify capsule ownership. CODE_MAP and operating/docs routes were updated.
- The Project Capsule now describes the actual schema, private archive, QA history and retired C2/C3 status; truthful reviewed-commit freshness is refreshed after watched code commits, with FAST/FULL validation.
- GitHub rejected unpublished commits exposing a private author email. Those commits were preserved in a verified private bundle and rewritten with the account-verified noreply identity, then pushed normally. No force push or account privacy change. An upstream Salute documentation change was preserved and is not claimed as this goal's work.

## Follow-up integration and device checks

- The combined delivery owner advanced NV exactly once from canonical 69 to 70. Primary installation is still pending; corrective builds retain 70.
- PR #104 was created through repo-task, attached to this chat and queued with the existing integrator. There is no manual merge or bypass.
- CI exposed a patch-review fixture still declaring schema 24. It now uses the canonical schema constant, preserving the actual compatibility rejection in production.
- Full Notifications testing exposed an open singleton connection left by the public History API test. Test cleanup now releases both its direct helper and the singleton. The unchanged collector ordering/drain/persistence test and all 21 module tests pass together.
- CI now runs the new v24-to-v25 migration, installer, QA-history and connected-gate safety suites in its existing host validation stage.
- CI instrumentation retains the full assertion output when a case fails, rather than discarding the stack through an output filter.
- All five existing CI workflows use Node 24-compatible checkout/setup-gradle v5 actions where applicable. The official tagged action metadata was checked. Ubuntu is pinned to the same 24.04 image used by the failed job, avoiding today's ubuntu-latest image migration. The emulator job explicitly installs its missing unversioned X11 development libraries.
- Actual Pixel Wi-Fi-off/LTE/Tailscale shell access passed, including a 6.8-second first handoff probe and the faster following samples; raw numeric latencies and genuine failures remain in the private SQLite history. Wi-Fi enable restoration is awaited rather than assumed immediate.
- A real Tailscale interruption was observed while commands continued through a freshly identity-verified independent LAN endpoint. The switch-based automatic reconnection failed; the observed Connect control restored the VPN and the Pixel identity was reverified. This is not recorded as an automated end-to-end PASS.
- All Pixel windows were closed with both notices. The user switched ordinary QA back to the emulator; the current MegaVault PersonalHub and project emulator instructions were re-read. Start/readiness/stop use only the canonical facade. Physical-only guards remain intact.
- The first minified/signed version70 candidate was built and frozen with its R8 mapping, and its exact bytes installed on the canonical emulator after a real externally validated24-to25 migration. It failed first-foreground prompt acceptance and is retained as FAILED_QA. The primary Pixel remains69/schema24; nothing has been published. Corrected candidates retain NV70 and restart immutable-artifact acceptance. QA69 artifacts and the emulator rollback pair remain preserved privately.

Official action metadata: https://github.com/actions/checkout/blob/v5/action.yml and https://github.com/gradle/actions/blob/v5/setup-gradle/action.yml .

- Full lint and architecture pass with0 errors. The first393 diagnostics (365 warnings+28 hints) are down to195 remaining warnings. Both source-backed counts are deduplicated; current text snippets match source, and remaining categories stay open in LINT.md. This does not constitute clean-lint or final-release acceptance.
- Widget configuration returns a fresh Intent containing only the widget ID instead of forwarding the incoming external Intent and its grants/extras. The Substances restore receiver accepts only its four manifest-declared system actions before scheduling work.
- Timer benchmark tab tracing uses async sections across coroutine/frame transitions and closes the pending section when composition is disposed. Trace names remain the same; async platform tracing is guarded on API29+.
- Since When and Life Periods read LocalResources during composition; Timer's hub adapter directly reuses its existing owned fallback string, removing reflective lookup and the redundant hardcoded fallback. The private Material hour-suffix override was redundant and removed.

- Removed98 unconsumed default app strings after the exact consumer scan, explicit prefix-match review and resource-owner comparison; retained the core-owned live definition. No unrelated translation was added.
- Replaced28 non-null numeric Compose states with primitive state implementations, including the explicit recomposition-counter intValue read. This avoids boxing without changing values or clock/update semantics; no live performance gain is claimed.
- The database-startup blocker now uses English non-translatable resources for title, schema/readiness details and Close. The data-preserving gate and recovery instructions remain intact.
- Reviewed conservative lint false positives: DatabaseGate retains only applicationContext; the process-owned call overlay is created from applicationContext and removed/cleared on dismissal. Narrow class-level StaticFieldLeak annotations document those intentional lifetimes. The pure SDK policy's inlined integer package-source constants receive a method-level InlinedApi explanation; the existing API36 guard remains.

- People external URL and explicit Maps opening now attempts the launch directly, with the existing failure fallback, instead of relying on package-visibility preflight queries. No queries/permissions added. Guidance: https://developer.android.com/training/package-visibility/use-cases#open-urls .
- Reordered Modifier to the first optional argument in six composable signatures after the exact consumer scan/readback; all affected consumers use named arguments. Highest app compilation passes.
- Widget target-cell dimensions now use API31 XML variants while older versions keep the same fallback geometry. Removed duplicate full-screen manifest attributes already set by the existing guarded Activity code. Quick-session widget uses its existing accessible name and an English non-translatable symbol resource.
- Removed eight SDK compatibility branches below the declared supported module minima; exact-alarm authorization/recovery and notification authorization retain their existing gates. Eleven core alert/link/notification tests pass (SQLite51–54); four call-overlay cancellation/restricted-settings policy tests pass (55).
- Espresso Intents now uses the existing version catalog and espresso3.7.0 version reference, preserving the coordinate/version. People loading labels use ellipses.
- Corrected37 count-wording diagnostics: neutral category/count labels retain every format argument; People age and elapsed-time units reuse shared core/ui plurals for natural singular/plural output. Removed five obsolete localized format entries, with zero translated additions.
- Removed ten unused pre26 fallback launcher bitmap variants from Timer and the redundant v26 resource directory qualifier. Both adaptive XML resources and all five foreground densities remain defined; foreground bytes were hash-checked unchanged.
- Closed stale GitHub issues91 and69 as completed after integration/evidence readback;71 as not planned because its C2/C3 orchestration was retired. Issue68 remains open pending Capsule validation on the actually integrated canonical checkout.

## Evidence and remaining delivery work

- Final candidate70-f949f4b1 built, canonically signed and frozen before QA;238 official OsmAnd wire classes remain named. Real Pixel paired24→25 migration/install verified the exact APK hash and preserves the original69 APK/database rollback pair. First-foreground prompt, Back resistance, explicit OK persistence and exactly one new shared Audit/History semantic update fact pass. Two actual Home startup measurements and three native cases are stored in SQLite64–66.
- Cross-snapshot preservation check covers all99 Room24 tables and passes (SQLite67). The existing package-replaced restore path retires28 already-expired notification scheduler rows and advances mutation generation; every original domain value/canonical ID and all future scheduler state remain preserved.
- Fedora/Telegram start and completion notices are verified; the Pixel window is closed. The latest user override forbids emulator use after the host crash, so subsequent QA uses Pixel/TCL. Failed emulator evidence is retained as such, with physical acceptance recorded separately.

The reviewed AndroidX cleanup converts ordinary asynchronous preference edits in Home, Workflowy, Timer counters/snapshots/widgets, Notifications status, Finance settings, Substances preferences/widgets and Git synchronization status. The same keys, values, batching and asynchronous apply behavior are retained. Widget deletion still clears every requested ID in one editor. Receipt consent/aliases, profile switching, the open manifest and recovery/version markers retain checked synchronous commits with explicit lint explanations; no commit/rollback/validation statement in the seven reviewed critical files changed. Finance bitmap creation/scaling uses the equivalent existing AndroidX defaults; drawing rotation now restores the canvas in a finally block. No dependency version, permission, Room schema or physical-device guard changes are part of this cleanup. Compilation passes; all21 focused host cases pass (SQLite56–62), and full unfiltered lint/architecture passes with0 errors and26 available-version suggestions.

Native QA history includes passing live search/tag-filter lifecycle, all eight overlapping candidates with cancel/explicit eighth selection/current visit/checkout, Where was I, all compact actions, twelve History scopes, real notification capture into shared History, Countdown, permission guidance, real external OsmAnd and Explore detail return. Failed attempts remain historical; later PASS is linked by scenario/artifact provenance rather than rewriting old records.

The primary Pixel now runs the exact frozen minified/signed70 artifact on externally migrated schema25. First-foreground/OK/single-update-fact acceptance passes, with data/rollback preserved and both completion notices verified. The emulator is stopped and explicitly forbidden by the latest user instruction. Source NV remains70. Remaining work: exact-artifact feature QA and Audit/History UI readback, protected source integration, publication, original-inventory dispositions and operational Git purification. The primary migration, installed-byte verification and first-foreground/persistent-OK/single-fact checks are complete.

Passive archive metadata readback found actual Telegram/WhatsApp events and no Signal/Google Messages events. No private message/title/sender/key was extracted. The original Notification Access setting is currently disabled and was preserved. This is coverage evidence, not invented incoming-message acceptance.

## Changed project files

All changed project paths against the initial integrated base, including current owned changes (267 paths):

- `.codex/CODE_MAP.tsv`
- `.gitattributes`
- `.github/workflows/android-consumer-preflight.yml`
- `.github/workflows/android-instrumentation-ci.yml`
- `.github/workflows/android-unit-ci.yml`
- `.github/workflows/architecture-boundaries.yml`
- `.github/workflows/play-store-preflight.yml`
- `AGENTS.md`
- `NV.txt`
- `app/build.gradle.kts`
- `app/src/androidTest/java/com/gernalix/personalhub/CountdownQaDeviceTest.kt`
- `app/src/androidTest/java/com/gernalix/personalhub/GlobalDatabaseInstrumentedTest.kt`
- `app/src/androidTest/java/com/gernalix/personalhub/HistoryAndPatchReviewDeviceTest.kt`
- `app/src/androidTest/java/com/gernalix/personalhub/HistoryUpgradeDeviceTest.kt`
- `app/src/androidTest/java/com/gernalix/personalhub/HubContextPersistenceDeviceTest.kt`
- `app/src/androidTest/java/com/gernalix/personalhub/HubDiscoverabilityEpisodesEmulatorTest.kt`
- `app/src/androidTest/java/com/gernalix/personalhub/HubHistorySearchQaDeviceTest.kt`
- `app/src/androidTest/java/com/gernalix/personalhub/HubSearchFieldDeviceTest.kt`
- `app/src/androidTest/java/com/gernalix/personalhub/HubTemporalDeferredQaDeviceTest.kt`
- `app/src/androidTest/java/com/gernalix/personalhub/LegacySnapshotCompactionDeviceTest.kt`
- `app/src/androidTest/java/com/gernalix/personalhub/ModuleSearchCursorQaDeviceTest.kt`
- `app/src/androidTest/java/com/gernalix/personalhub/NotificationsHistoryQaDeviceTest.kt`
- `app/src/androidTest/java/com/gernalix/personalhub/OsmAndExploreQaDeviceTest.kt`
- `app/src/androidTest/java/com/gernalix/personalhub/PersistenceSchemaCoverageDeviceTest.kt`
- `app/src/androidTest/java/com/gernalix/personalhub/PersistenceUiRegressionDeviceTest.kt`
- `app/src/androidTest/java/com/gernalix/personalhub/PlacesPixelQaDeviceTest.kt`
- `app/src/androidTest/java/com/gernalix/personalhub/PlacesSearchDraftDeviceTest.kt`
- `app/src/androidTest/java/com/gernalix/personalhub/QuickEventRelatedPersistenceDeviceTest.kt`
- `app/src/androidTest/java/com/gernalix/personalhub/SinceWhenAutonomyDeviceTest.kt`
- `app/src/androidTest/java/com/gernalix/personalhub/SinceWhenEditorRoundTripDeviceTest.kt`
- `app/src/androidTest/java/com/gernalix/personalhub/SubstanceInternalPersistenceDeviceTest.kt`
- `app/src/main/java/com/gernalix/personalhub/AppUpdateTracker.kt`
- `app/src/main/java/com/gernalix/personalhub/HubActivityPresentation.kt`
- `app/src/main/java/com/gernalix/personalhub/HubAuditLogScreen.kt`
- `app/src/main/java/com/gernalix/personalhub/HubDeepLinkActivity.kt`
- `app/src/main/java/com/gernalix/personalhub/HubHistorySearchScreen.kt`
- `app/src/main/java/com/gernalix/personalhub/HubTagsActivity.kt`
- `app/src/main/java/com/gernalix/personalhub/MainActivity.kt`
- `app/src/main/java/com/gernalix/personalhub/PersonalHubApplication.kt`
- `app/src/main/java/com/gernalix/personalhub/SemanticHistoryPresentation.kt`
- `app/src/main/java/com/gernalix/personalhub/SinceWhenActivity.kt`
- `app/src/main/java/com/gernalix/personalhub/SinceWhenCreateScreen.kt`
- `app/src/main/java/com/gernalix/personalhub/SinceWhenScreen.kt`
- `app/src/main/java/com/gernalix/personalhub/capsules/settings/GitHistoryTools.kt`
- `app/src/main/java/com/gernalix/personalhub/capsules/settings/HubSettings.kt`
- `app/src/main/java/com/gernalix/personalhub/sincewhen/CounterElapsed.kt`
- `app/src/main/java/com/gernalix/personalhub/sincewhen/SinceWhenEditor.kt`
- `app/src/main/java/com/gernalix/personalhub/workflowydays/WorkflowyDays.kt`
- `app/src/main/res/values/since_when.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/test/AndroidManifest.xml`
- `app/src/test/java/com/gernalix/personalhub/AppUpdateTrackerTest.kt`
- `app/src/test/java/com/gernalix/personalhub/HubActivityPresentationTest.kt`
- `app/src/test/java/com/gernalix/personalhub/ProfileRuntimeCoordinatorTest.kt`
- `app/src/test/java/com/gernalix/personalhub/sincewhen/CounterElapsedTest.kt`
- `build.gradle.kts`
- `contracts/database/src/main/java/com/gernalix/personalhub/contracts/database/HubTagEntities.kt`
- `contracts/database/src/main/java/com/gernalix/personalhub/contracts/database/SinceWhenCounterEntity.kt`
- `core/alerts/src/main/java/com/gernalix/personalhub/core/alerts/AlertLinkPolicy.kt`
- `core/alerts/src/main/java/com/gernalix/personalhub/core/alerts/AlertNotificationDispatcher.kt`
- `core/alerts/src/main/java/com/gernalix/personalhub/core/alerts/HubAndroidPlatform.kt`
- `core/alerts/src/test/java/com/gernalix/personalhub/core/alerts/AlertNotificationTapTest.kt`
- `core/database/schemas/com.gernalix.personalhub.core.database.PersonalHubDatabase/25.json`
- `core/database/src/androidTest/java/com/gernalix/personalhub/core/database/capsules/gitdata/GitDataRestoreDeviceTest.kt`
- `core/database/src/main/java/com/gernalix/personalhub/core/database/DatabaseGate.kt`
- `core/database/src/main/java/com/gernalix/personalhub/core/database/DatabaseProfiles.kt`
- `core/database/src/main/java/com/gernalix/personalhub/core/database/DatabaseStartupGate.kt`
- `core/database/src/main/java/com/gernalix/personalhub/core/database/DatabaseVault.kt`
- `core/database/src/main/java/com/gernalix/personalhub/core/database/PersonalHubDatabase.kt`
- `core/database/src/main/java/com/gernalix/personalhub/core/database/capsules/gitdata/GitDataFormat.kt`
- `core/database/src/main/java/com/gernalix/personalhub/core/database/capsules/gitdata/GitDataSettings.kt`
- `core/database/src/main/java/com/gernalix/personalhub/core/database/capsules/history/HistoryQueryCapsule.kt`
- `core/database/src/main/java/com/gernalix/personalhub/core/database/capsules/identity/CanonicalIdentityCapsule.kt`
- `core/database/src/main/java/com/gernalix/personalhub/core/database/capsules/mutationevents/MutationEventCapture.kt`
- `core/database/src/main/java/com/gernalix/personalhub/core/database/capsules/mutationevents/MutationEventStore.kt`
- `core/database/src/main/res/values/strings.xml`
- `core/database/src/test/java/com/gernalix/personalhub/core/database/CanonicalIdentityCapsuleTest.kt`
- `core/database/src/test/java/com/gernalix/personalhub/core/database/HistoryQueryCapsuleTest.kt`
- `core/database/src/test/java/com/gernalix/personalhub/core/database/capsules/gitdata/GitDataFinalValidationTest.kt`
- `core/database/src/test/java/com/gernalix/personalhub/core/database/capsules/sync/SyncJournalTest.kt`
- `core/hub-context/src/main/java/com/gernalix/personalhub/core/hubcontext/HubContextExplorer.kt`
- `core/hub-context/src/main/java/com/gernalix/personalhub/core/hubcontext/HubContextLinks.kt`
- `core/hub-context/src/main/java/com/gernalix/personalhub/core/hubcontext/ResourceHubAdapter.kt`
- `core/hub-context/src/main/java/com/gernalix/personalhub/core/hubcontext/SharedTagEngine.kt`
- `core/hub-context/src/main/java/com/gernalix/personalhub/core/hubcontext/WorkflowyIntegration.kt`
- `core/hub-context/src/test/java/com/gernalix/personalhub/core/hubcontext/SharedTagEngineTest.kt`
- `core/location/src/main/java/com/gernalix/personalhub/core/location/LocationSuggestions.kt`
- `core/ui/src/main/java/com/gernalix/personalhub/core/ui/HubUiPrimitives.kt`
- `core/ui/src/main/java/com/gernalix/personalhub/core/ui/ModuleHistoryHost.kt`
- `core/ui/src/main/java/com/gernalix/personalhub/core/ui/SearchEditBuffer.kt`
- `core/ui/src/main/res/values/countdown.xml`
- `core/ui/src/main/res/values/git_patch_review.xml`
- `core/ui/src/main/res/values/history_notifications.xml`
- `core/ui/src/main/res/values/history_query.xml`
- `core/ui/src/main/res/values/home_layout.xml`
- `core/ui/src/main/res/values/module_history.xml`
- `core/ui/src/test/java/com/gernalix/personalhub/core/ui/SearchEditBufferTest.kt`
- `docs/ANDROID_TEST_HISTORY.md`
- `docs/DATABASE_SCHEMA_UPGRADES.md`
- `docs/qa/ph-completion-20261019/CHANGES.md`
- `docs/qa/ph-completion-20261019/LINT.md`
- `docs/qa/ph-completion-20261019/README.md`
- `feature/luoghi/build.gradle.kts`
- `feature/luoghi/consumer-rules.pro`
- `feature/luoghi/libs/OSMAND-LICENSE.txt`
- `feature/luoghi/libs/README.md`
- `feature/luoghi/libs/osmand-aidl-api-20261019-sources.jar`
- `feature/luoghi/libs/osmand-aidl-api-20261019.jar`
- `feature/luoghi/src/main/AndroidManifest.xml`
- `feature/luoghi/src/main/java/com/gernalix/luoghi/LuoghiHomeViewModel.kt`
- `feature/luoghi/src/main/java/com/gernalix/luoghi/MainActivity.kt`
- `feature/luoghi/src/main/java/com/gernalix/luoghi/MapViewerActivity.kt`
- `feature/luoghi/src/main/java/com/gernalix/luoghi/capsules/checkin/CheckInCapsule.kt`
- `feature/luoghi/src/main/java/com/gernalix/luoghi/capsules/checkin/CheckInPolicy.kt`
- `feature/luoghi/src/main/java/com/gernalix/luoghi/capsules/checkin/WhereWasIQuery.kt`
- `feature/luoghi/src/main/java/com/gernalix/luoghi/capsules/explore/PlacesProximity.kt`
- `feature/luoghi/src/main/java/com/gernalix/luoghi/capsules/geofence/PlaceGeofenceRegistrar.kt`
- `feature/luoghi/src/main/java/com/gernalix/luoghi/capsules/mapviewer/MapViewerCapsule.kt`
- `feature/luoghi/src/main/java/com/gernalix/luoghi/capsules/mapviewer/MapViewerModels.kt`
- `feature/luoghi/src/main/java/com/gernalix/luoghi/capsules/mapviewer/MapViewerRepository.kt`
- `feature/luoghi/src/main/java/com/gernalix/luoghi/capsules/mapviewer/OsmAndBridge.kt`
- `feature/luoghi/src/main/java/com/gernalix/luoghi/capsules/places/PlaceSearch.kt`
- `feature/luoghi/src/main/java/com/gernalix/luoghi/capsules/places/PlacesCapsule.kt`
- `feature/luoghi/src/main/java/com/gernalix/luoghi/data/PlaceRepository.kt`
- `feature/luoghi/src/main/java/com/gernalix/luoghi/provider/PlacesContract.kt`
- `feature/luoghi/src/main/java/com/gernalix/luoghi/ui/explore/ExplorePlacesScreen.kt`
- `feature/luoghi/src/main/java/com/gernalix/luoghi/ui/home/HomeScreen.kt`
- `feature/luoghi/src/main/java/com/gernalix/luoghi/ui/visits/VisitDialogs.kt`
- `feature/luoghi/src/main/java/com/gernalix/luoghi/ui/visits/VisitTimelineItem.kt`
- `feature/luoghi/src/main/java/com/gernalix/luoghi/ui/visits/VisitsScreen.kt`
- `feature/luoghi/src/main/res/values/explore_osmand.xml`
- `feature/luoghi/src/main/res/values/strings.xml`
- `feature/luoghi/src/test/java/com/gernalix/luoghi/CheckInAccuracyPolicyTest.kt`
- `feature/luoghi/src/test/java/com/gernalix/luoghi/OsmAndMapPlanTest.kt`
- `feature/luoghi/src/test/java/com/gernalix/luoghi/PlaceSearchTest.kt`
- `feature/luoghi/src/test/java/com/gernalix/luoghi/PlaceTagsLifecycleTest.kt`
- `feature/luoghi/src/test/java/com/gernalix/luoghi/PlacesHistoryMapGeofencingTest.kt`
- `feature/luoghi/src/test/java/com/gernalix/luoghi/PlacesProximityTest.kt`
- `feature/luoghi/src/test/java/com/gernalix/luoghi/VisitTimestampTest.kt`
- `feature/multitimetracker/src/androidTest/java/com/example/multitimetracker/CanonicalTimerFixtures.kt`
- `feature/multitimetracker/src/androidTest/java/com/example/multitimetracker/capsules/now/ui/NowTimerRulesRegressionInstrumentedTest.kt`
- `feature/multitimetracker/src/androidTest/java/com/example/multitimetracker/capsules/now/ui/QuickStartIdleLayoutInstrumentedTest.kt`
- `feature/multitimetracker/src/androidTest/java/com/example/multitimetracker/capsules/now/ui/QuickStartTagLauncherInstrumentedTest.kt`
- `feature/multitimetracker/src/androidTest/java/com/example/multitimetracker/capsules/now/ui/QuickStartTimedHierarchyInstrumentedTest.kt`
- `feature/multitimetracker/src/androidTest/java/com/example/multitimetracker/ui/components/SessionEditDialogRegressionInstrumentedTest.kt`
- `feature/multitimetracker/src/main/AndroidManifest.xml`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/MainActivity.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/MainViewModel.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/RandomTimerStore.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/TimeFenceNotifier.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/TimeFenceTimerScheduler.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/capsules/alerts/ui/AlertsScreen.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/capsules/now/ui/NowCapsuleUi.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/capsules/now/ui/NowScreen.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/capsules/quickevents/ui/QuickEventsScreen.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/capsules/sincewhen/ui/LifePeriodsScreen.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/debug/DebugRecomposeCounter.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/hub/TimerQuickEventEntryHubAdapter.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/hub/TimerSessionHubAdapter.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/persistence/CriticalDataGuard.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/persistence/SessionRepository.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/persistence/SnapshotSqlite.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/persistence/SnapshotStore.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/persistence/SyncCountersStore.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/persistence/UiPrefsStore.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/ui/AppRoot.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/ui/components/AlertPopupHost.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/ui/components/AppTopBar.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/ui/components/PrecisionTimelineTrack.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/ui/components/RunningSessionRow.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/ui/components/SectionHeader.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/ui/components/SessionEditDialog.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/ui/components/SingleSubmitButtons.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/ui/util/RewardToastManager.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/widget/QuickEventWidgetClickActivity.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/widget/QuickEventWidgetConfigureActivity.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/widget/QuickEventWidgetPrefs.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/widget/QuickTaskWidgetClickActivity.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/widget/QuickTaskWidgetProvider.kt`
- `feature/multitimetracker/src/main/res/layout/widget_quick_task.xml`
- `feature/multitimetracker/src/main/res/mipmap-anydpi/ic_launcher.xml`
- `feature/multitimetracker/src/main/res/mipmap-anydpi/ic_launcher_round.xml`
- `feature/multitimetracker/src/main/res/mipmap-hdpi/ic_launcher.png`
- `feature/multitimetracker/src/main/res/mipmap-hdpi/ic_launcher_round.png`
- `feature/multitimetracker/src/main/res/mipmap-mdpi/ic_launcher.png`
- `feature/multitimetracker/src/main/res/mipmap-mdpi/ic_launcher_round.png`
- `feature/multitimetracker/src/main/res/mipmap-xhdpi/ic_launcher.png`
- `feature/multitimetracker/src/main/res/mipmap-xhdpi/ic_launcher_round.png`
- `feature/multitimetracker/src/main/res/mipmap-xxhdpi/ic_launcher.png`
- `feature/multitimetracker/src/main/res/mipmap-xxhdpi/ic_launcher_round.png`
- `feature/multitimetracker/src/main/res/mipmap-xxxhdpi/ic_launcher.png`
- `feature/multitimetracker/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png`
- `feature/multitimetracker/src/main/res/values/strings.xml`
- `feature/multitimetracker/src/main/res/xml-v31/quick_event_widget_info.xml`
- `feature/multitimetracker/src/main/res/xml-v31/quick_task_widget_info.xml`
- `feature/multitimetracker/src/main/res/xml/quick_event_widget_info.xml`
- `feature/multitimetracker/src/main/res/xml/quick_task_widget_info.xml`
- `feature/notifications/src/main/java/com/gernalix/personalhub/notifications/api/NotificationsHistory.kt`
- `feature/notifications/src/main/java/com/gernalix/personalhub/notifications/api/NotificationsRoute.kt`
- `feature/notifications/src/main/java/com/gernalix/personalhub/notifications/capsules/archive/ArchiveStore.kt`
- `feature/notifications/src/main/java/com/gernalix/personalhub/notifications/capsules/archive/CollectorService.kt`
- `feature/notifications/src/main/java/com/gernalix/personalhub/notifications/capsules/archive/ConversationsScreen.kt`
- `feature/notifications/src/main/java/com/gernalix/personalhub/notifications/capsules/archive/NormalizedArchive.kt`
- `feature/notifications/src/main/java/com/gernalix/personalhub/notifications/capsules/archive/NotificationSnapshot.kt`
- `feature/notifications/src/main/java/com/gernalix/personalhub/notifications/capsules/archive/NotificationsActivity.kt`
- `feature/notifications/src/main/java/com/gernalix/personalhub/notifications/capsules/archive/PeopleBridge.kt`
- `feature/notifications/src/test/java/com/gernalix/personalhub/notifications/capsules/archive/ArchiveStoreTest.kt`
- `feature/notifications/src/test/java/com/gernalix/personalhub/notifications/capsules/archive/CollectorServiceTest.kt`
- `feature/notifications/src/test/java/com/gernalix/personalhub/notifications/capsules/archive/ConversationsTest.kt`
- `feature/soldi/src/androidTest/java/com/gernalix/personalhub/soldi/BankingRenderDeviceTest.kt`
- `feature/soldi/src/debug/java/com/gernalix/personalhub/soldi/FinanceSemanticQaBridge.kt`
- `feature/soldi/src/main/java/com/gernalix/personalhub/soldi/BankingScreen.kt`
- `feature/soldi/src/main/java/com/gernalix/personalhub/soldi/BankingSecureStore.kt`
- `feature/soldi/src/main/java/com/gernalix/personalhub/soldi/FinancePhotoSemanticEngine.kt`
- `feature/soldi/src/main/java/com/gernalix/personalhub/soldi/FinanceReminderScheduler.kt`
- `feature/soldi/src/main/java/com/gernalix/personalhub/soldi/SoldiActivity.kt`
- `feature/soldi/src/main/java/com/gernalix/personalhub/soldi/SoldiEditorsUi.kt`
- `feature/soldi/src/main/java/com/gernalix/personalhub/soldi/SoldiUiModels.kt`
- `feature/soldi/src/main/java/com/gernalix/personalhub/soldi/receipt/ReceiptImport.kt`
- `feature/soldi/src/main/java/com/gernalix/personalhub/soldi/receipt/ReceiptParser.kt`
- `feature/soldi/src/main/res/values-it/banking_strings.xml`
- `feature/soldi/src/main/res/values/banking_strings.xml`
- `feature/soldi/src/test/java/com/gernalix/personalhub/core/database/capsules/soldi/FinanceAccountsTest.kt`
- `feature/soldi/src/test/java/com/gernalix/personalhub/core/database/capsules/soldi/FinanceCapsuleTest.kt`
- `feature/sostanze/src/main/java/com/gernalix/sostanze/MainActivity.kt`
- `feature/sostanze/src/main/java/com/gernalix/sostanze/notifications/SostanzeNotificationScheduler.kt`
- `feature/sostanze/src/main/java/com/gernalix/sostanze/notifications/SostanzeRandomAlertStore.kt`
- `feature/sostanze/src/main/java/com/gernalix/sostanze/ui/SostanzeApp.kt`
- `feature/sostanze/src/main/java/com/gernalix/sostanze/widget/SubstancesWidgetConfigureActivity.kt`
- `feature/sostanze/src/main/java/com/gernalix/sostanze/widget/SubstancesWidgetPrefs.kt`
- `feature/sostanze/src/main/res/values-it/strings.xml`
- `feature/sostanze/src/main/res/values/strings.xml`
- `feature/sostanze/src/main/res/xml-v31/substances_widget_info.xml`
- `feature/sostanze/src/main/res/xml/substances_widget_info.xml`
- `feature/sostanze/src/test/java/com/gernalix/sostanze/SostanzeCampaignTest.kt`
- `feature/sostanze/src/test/java/com/gernalix/sostanze/widget/SubstancesWidgetTest.kt`
- `feature/supercontacts/src/main/java/com/supercontacts/app/CallOverlayPermission.kt`
- `feature/supercontacts/src/main/java/com/supercontacts/app/CallSystemOverlayController.kt`
- `feature/supercontacts/src/main/java/com/supercontacts/app/MainActivity.kt`
- `feature/supercontacts/src/main/java/com/supercontacts/app/data/repository/ContactModels.kt`
- `feature/supercontacts/src/main/java/com/supercontacts/app/ui/app/SuperContactsApp.kt`
- `feature/supercontacts/src/main/res/values/strings.xml`
- `feature/wordpulse/src/main/java/com/wordpulse/app/MainActivity.kt`
- `feature/wordpulse/src/main/java/com/wordpulse/app/ui/WordPulseViewModel.kt`
- `feature/wordpulse/src/test/java/com/wordpulse/app/domain/AlertnessTest.kt`
- `gradle/libs.versions.toml`
- `gradle/wrapper/gradle-wrapper.jar`
- `gradle/wrapper/gradle-wrapper.properties`
- `gradlew`
- `gradlew.bat`
- `project-capsule.yaml`
- `tools/android_connected_test_gate.py`
- `tools/android_paired_cutover_v24.py`
- `tools/android_pixel_apk.py`
- `tools/android_shortcut_alias_smoke.py`
- `tools/android_test_history.py`
- `tools/android_ui_summary.py`
- `tools/migrate_personalhub_v24_to_v25.py`
- `tools/run_ci_instrumentation.sh`
- `tools/test_android_connected_test_gate.py`
- `tools/test_android_paired_cutover_v24.py`
- `tools/test_android_pixel_apk.py`
- `tools/test_android_shortcut_alias_smoke.py`
- `tools/test_android_test_history.py`
- `tools/test_android_ui_summary.py`
- `tools/test_migrate_personalhub_v24_to_v25.py`
- `tools/test_run_ci_instrumentation.py`

## Dependency compatibility follow-up (in progress)

- Updated Google Places4.4.1 to6.0.2 after direct inspection of the official AAR. Replaced the removed model fields/getters with their supported equivalents and removed the obsolete deprecation suppression; the suggestion provider contract and session handling are preserved. No Cloud/API-key/billing setting changed. Consumer closure passes; compilation and focused verification are pending. This changes app inputs and requires a new frozen candidate with repeated final QA; the prior70 APK/evidence are retained.

- Aligned Kotlin/Compose compiler plugins2.2.21→2.4.21 because the Places6 artifact actually contains Kotlin2.4 metadata. Explicitly typed four mixed SQL binding arrays for Kotlin2.4 inference, preserving every SQL statement, binding value/order and transaction boundary. Compilation failures remain evidence and corrective validation is in progress.

- Completed the Places6/Kotlin2.4 compile gate and28 focused host cases plus architecture checks. The final SQL compatibility count is12 arrays across six production files, plus2 arrays in existing fixtures. The added types do not change SQL, binding values/order, assertions or transaction boundaries.
- Updated the14 remaining stable version keys in the catalog and began canonical Gradle9.8.1 wrapper regeneration with the official SHA256. The exact previous/selected matrix and compatibility sources are in LINT.md. Combined-matrix compile/test/full-lint verification is pending; no new APK has been built or installed.

- Added a narrowly scoped Git whitespace attribute for the generated CRLF Windows launcher. All standard blank-at-EOL/EOF and space-before-tab checks remain enabled; canonical wrapper bytes are preserved. `git diff --check` now passes without treating valid CR line endings as trailing spaces.
- Prepared a private final-device readback controller with explicit artifact/repository selection and manifest-derived version labels, retaining notification, installed-hash, foreground and cleanup guards. It has not been executed against a replacement candidate.

- Corrected Robolectric/JDK shared-memory setup by exporting `java.base/jdk.internal.access` only in Gradle Test JVMs. All10 previously failed Timer tests now pass without changing their assertions or lowering SDK coverage; original failures remain in SQLite. The complete gate is being rerun after this test-environment correction.

- Recovered one distinct saved Substances widget through the real Nova picker and explicit Android binding permission, without recording an intake. Real saved target/binding preservation across owned package suspension and restart now passes; replacement-install preservation remains pending.
- Hardened the private physical UI controllers against stale Android hierarchy files and unexpected foregrounds; only the observed Android widget-binding permission Activity is allowed during recovery. Interrupted alarm windows preserve their evidence and verified completion notices.

- Applied explicit types to seven additional mixed SQL arrays in five existing Android test fixture files for Kotlin2.4; original SQL, values/order, assertions and physical safety guards are unchanged.
- Removed the now-explicitly empty ViewModel superclass `onCleared` call in WordPulse; the existing PVT cancellation is preserved.
- Added system-bar, cutout and keyboard insets to the existing Substances and Timer event widget configuration layouts, preserving their spacing and selection behavior. This repairs the title/status-bar overlap observed on the primary Pixel. Uses the already declared AndroidX API: https://developer.android.com/develop/ui/views/layout/edge-to-edge . Highest app compile PASS; final native visual check pending.
- Shared android_ui_summary now uses a fresh per-call hierarchy filename, requires Android to confirm that file, validates the XML and cleans only its own file. Three focused host regressions pass, including Android's zero-exit dump failure with stale prior XML.
- Expanded Project Capsule freshness watches to the real wrapper scripts, NV, tools and scoped Git whitespace policy. Its old review ref correctly fails while source is uncommitted; refresh and canonical validation remain required.

Latest source validation: highest app compile, Android instrumentation source compilation, targeted widget7/7, architecture and all14 full lint reports PASS with0 errors/0 warnings; prior full unit298/298 retained separately. No final replacement APK has been built or installed for these dependency/UI changes.

- Advanced source NV70→71 for the next real primary Pixel replacement, after live version70 and canonical main69 readback. The user explicitly requires a new NV for every actual update; old70 artifacts and acceptance remain historical. No QA-only compilation or test increments NV. Version71 still requires minified signing, real update-prompt/event/binding/feature acceptance and exact-byte publication.

## Compiler and native follow-up after version71

- Preserved actual71 install/update6 PASS,150-table data comparison and six startup measurements. Derived only actually emitted native case names/statuses into JUnit; original failed/incomplete suites remain. Corrected private QA handling of absent default preferences, existing Since when title casing and owner tab selection. Unexpected Telegram foreground stops input.
- Corrected the private artifact verifier to match all program DEX R8 map IDs against the actual minify task mapping; rejected old output-directory mapping is retained. APK71 bytes never changed.
- Fixed nullable mixed-array inference in the existing Timer library fixture, retaining its isolated-package guard and exact SQL/values.
- Made four additional mixed arrays explicit for future Kotlin compatibility; removed30 compiler-proven redundant null operators at their exact locations plus the downstream redundant session-tag safe call; removed three unreachable Elvis fallbacks without changing branches/data.
- Replaced old primary/scrollable tab APIs and existing top-bar color factory with current equivalents; migrated11 Compose test factory import files to v2, preserving assertions and physical guards.
- Moved the two clipboard copies to the current typed/suspending API with the exact original plain text; alert links now use LinkAnnotation while retaining the existing scheme/launch policy.
- Moved both swipe rows to Material3 onDismiss with guarded actions, explicit reset for edit/confirmation and the existing exactly-once deletion protection. Runtime verification remains pending.
- Preserved the exact notification profile ID with a local compatibility declaration: UserHandle has no public numeric identifier in the actual SDK, so the attempted replacement correctly failed compilation and was repaired. Used the canonical Since When namespace constant with the same literal. Legacy phone/location callback compatibility is documented narrowly. Heterogeneous allowlisted notification scalars retain their explicit type gate with one local deprecation declaration.
- Build scripts now use AGP asset directories and the public injected Gradle BuildFeatures service for the existing fail-closed signed-build Configuration Cache guard. Isolated persistence fixtures explicitly opt into their owning write API; annotation-coverage lookup now requires the expected annotation. None of these changes weakens device/data/signing protections.

These current source changes are under compilation, not final validation or delivery. A new actual Pixel app replacement will be72, once all source/CI gates are satisfied.

- Latest user instruction supersedes the emulator prohibition. Canonical Pixel_8a startup/readiness passed; isolated serial-targeted QA will close it through the facade in finally. Updated the external current inventory while preserving its initial snapshot byte-for-byte. Hardened the private physical readback against foreign interactive overlays covering a PH control; no device acceptance inferred.

## Full compilation and emulator follow-up

- Comprehensive visible-warning execution completed with101 fresh host JUnit reports:375 PASS,1 existing AndroidKeyStore-dependent SKIP,0 failures (SQLite312–412). Fresh full lint14 reports contain0 issues; all requested compilation tasks and architecture gate completed successfully (413). Compiler warnings are a separate open boundary.
- Removed the remaining compiler-proven nullable operators/dead fallbacks in the guarded cross-currency fee field and History range; kept all test assertions while removing redundant operators in ProfileRuntimeCoordinator and WordPulse test code.
- Removed ten now-redundant explicit SQLiteMode.NATIVE annotations. Native SQLite is the unchanged default in the selected Robolectric4.17; no legacy backend was selected. Official migration guidance: https://robolectric.org/upgrade_to_version_4/ . Replaced the two deprecated fake-browser registrations with the existing supported ActivityInfo/IntentFilter APIs, preserving the Workflowy tap assertions.
- Migrated six additional library Compose test factories to v2; the previous11 app factory files and assertions remain. Replaced the unused Unit loop expression in the native restore fixture with an empty body, preserving HTTP header reads.
- Extracted the four existing real Places module/ViewModel/Room/tag scenarios into a shared test base. The Pixel runner retains all original package/manufacturer/model/explicit-authorization checks; the new runner additionally requires the isolated QA package and actual emulator model/hardware. No physical guard was weakened; CODE_MAP now routes to these scenarios.
- Current highest-consumer quiet app compile PASS after these leaf edits. A nonincremental Kotlin compile and signed isolated QA build is running to expose warnings from unchanged files too. No Pixel update/NV increment/publication occurred.
- The remaining Gradle setVisible warning is traced to26 calls inside the official AGP9.4.1 source archive, confirmed as the latest stable Google Maven coordinate. No PH caller, stable replacement or warning suppression has been introduced; upstream diagnostic remains OPEN with private provenance.

- Native validation and harness repairs: the isolated Timer fixture still had schema24. Reused the existing external24→25 migrator and atomic stage/swap with coherent DB/WAL backup, projection preservation, integrity and on-device rollback; no data clearing or production guard change. Same Timer APK now37/37 PASS; Places library12, native restore2 and Banking UI2 also PASS.
- A real QA update71 modal blocked the legacy History button. Verified the exact pending native dialog and acknowledgement state, then used the actual OK control; no direct acknowledgement preference edit. The previously failing History entry passes. The next app suite exposed a separate live-search count timeout, now instrumented with exact query/count, lifecycle, foreground and pre-cleanup screenshot/UI capture. The existing assertions and10-second bound remain unchanged.
- Removed the remaining deprecated Since When alias in one host test, preserving its exact value; retained three synthetic notification priority constructors behind narrow compatibility declarations because the replacement overload is absent from the actual public SDK; every field and assertion is unchanged. The failed replacement compile is preserved.
- Repaired local native stripping prerequisites by installing the official AGP9.4 default NDK28.2.13676358. SDK-manager’s truncated/invalid ZIP attempt is retained; the replacement download verifies all byte ranges,722261334-byte total, official SHA1a7b54a5de87fecd125a17d54f73c446199e72a64, ZIP integrity, package revision and executable llvm-strip before installation. No native-library keep/suppression rule or APK bytes were altered manually. Packaging acceptance still pending.

## Places search draft and reproduced timeout

Isolated repetitions after a full16-case app PASS reproduced an empty query with10 unfiltered places instead of1 (SQLite431;429/430 PASS). The derived Home state combines Room, visits, statistics and context changes before echoing the text. HomeScreen now owns an immediate saveable edit draft outside LazyColumn and submits it to the existing filter callback, including after state restoration. This prevents delayed derived-state values from controlling the edit buffer. New PlacesSearchDraftDeviceTest deliberately holds the derived state unchanged and checks typing, scrolling, restoration and subsequent replacement. Native regression/repetitions remain pending at this checkpoint; no timeout or result assertion was weakened.

Search validation update: the new test initially failed because assertTextEquals combines a field label and EditableText, although the recorded EditableText was exactly correct (432;16 other cases PASS). The assertion now checks the exact EditableText property, preserving the intended invariant. Delayed-state/scroll/restoration regression PASS433 and four isolated real search repetitions PASS434–437. Both cases are now explicit CI selections; bash syntax validation PASS. The same production APK was reused unchanged while rebuilding only its corrected test APK.

Final local search checkpoint: full app16/16 PASS438 after the isolated regression and repetitions. All6 groups/21 executions PASS433–438;17 distinct cases. Canonical emulator stop PASS. Local app compile/QA build, Luoghi lint0 and architecture PASS. Final72 artifact, remote CI/integration and physical exact-artifact acceptance remain pending.

Source checkpointb6257dfb contains the validated compiler/API compatibility and synchronous Places search edits. Capsule reviewed_commit refreshed to that source checkpoint: FAST13/FULL14 PASS; SQLite records2 command gates, not27 unit tests. Remote CI/final72 acceptance remain pending.

## Cross-module search cursor investigation

The user reported spontaneous caret movement across module search filters. A new emulator baseline reproduced the common failure (SQLite440): inserting X in the middle of alpha beta reverted the edit text to alpha beta while the caret moved to6. This is separate evidence from the earlier Places query-loss timeout.

HubSearchField now retains a saveable TextFieldValue edit buffer, including selection and the IME composing range. Filter-state acknowledgements retire pending edits without feeding old text back into the IME. Genuine external saved-query/clear replacements remain supported; explicit clear resets the local buffer and returns focus. Selection-only events do not launch another search. Restoration retains text/selection and resubmits an outstanding filter draft; the composing range is deliberately not reused across a recreated IME session.

The21 existing use sites cover shared History/Tags, Places and Explore, Timer search/tag/quick-event/chain/trash pickers, Soldi search/person picker, Substances, People and WordPulse. Audit, Banking and Notifications archive/conversations/people picker now reuse the same component (5 additional use sites). Labels and callbacks are unchanged; Notifications retains its original multiline behavior through the new optional singleLine parameter. The read-only Timer tag picker display is not an editable query and remains unchanged. WordPulse typing capture is separate and untouched.

Host validation:7 cases PASS441–443, including4 buffer tests for late/conflated acknowledgements, cursor/composition retention and explicit external clear. App compile/QA build, architecture and4 fresh scoped lint reports0 issues PASS. Shared native edit/clear/restoration cases3 PASS444. Actual People/Substances cursor cases PASS in445; that3-case group is globally FAIL because the initial WordPulse launch queried Compose roots before an Activity root existed. Startup polling now handles the transient absence within the original10s deadline; cursor assertions are unchanged. The remaining native regression is in progress. All historical failures remain intact. Six new native cursor cases are explicit CI selections; shell syntax PASS.

Cursor native update: actual People/Substances/WordPulse group3/3 PASS446 and Places draft/scroll/restore PASS447 on the unchanged production QA APK. The5 newly shared fields also gain its existing clear button, with original labels, pagination callbacks and multiline mode preserved. No new string translation or physical-device interaction. App16 regression is running.

Cross-module cursor native closure: app16/16 PASS448; combined final groups444/446/447/448 are23 native cases PASS. All use the same production QA APK. Canonical emulator stop PASS. Native baseline440 is resolved by the unchanged regression assertion passing444. The module startup445 failure is resolved by446. This is source/isolated QA acceptance, not final minified72 acceptance or Pixel installation.

Source checkpoint2462c321 contains the cross-module cursor correction and all stated validation. Capsule reviewed_commit refreshed: FAST13/FULL14 PASS; SQLite449 records2 command gates, not27 unit tests. Remote CI and exact final72 acceptance remain pending.

Final72 preparation: Pixel metadata/hash readback confirms unchanged71, without UI/disruption. Emulator primary70 and OsmAnd present; facade stop verified. NV71→72 is the next actual primary replacement required by the user, while canonical main remains69; no second increment for pre-install retries. Focused new-visible-value source gate passed: cursor buffer internals never rendered, existing query labels preserved, no new raw backend values. Final build/QA/install/publication remain pending.

Version72 source4fd69d38 reviewed: Capsule FAST13/FULL14 PASS (SQLite450 contains2 command gates). The following metadata checkpoint does not alter app/build inputs. Final minified/signature/manifest/mapping verification and exact-byte native acceptance remain pending.
