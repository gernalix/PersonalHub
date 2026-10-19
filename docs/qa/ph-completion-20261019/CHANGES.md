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
- Fixed a first-foreground update-notice race exposed by the real minified candidate: the database startup gate recreates the first Activity. Observation now survives Activity replacement and presents only on the current resumed owner; background completion waits for foreground without acknowledging the update. Two deterministic lifecycle regressions and the existing one-fact Audit/History test pass. Live corrected final-candidate acceptance remains pending.
- AppUpdateTracker supplies the previously requested exact update prompt, OK acknowledgement and one idempotent semantic version-update event shared by Audit and History. A fresh install creates a baseline without a fictitious update. Real primary-app update acceptance remains pending.
- Addressed lint findings exposed by the full CI path: Explore resolves its unnamed-place resource in composition before click callbacks; the Places visit-state resource has a feature-specific name instead of colliding with Compose's private resource. Count labels work for zero/one/many without incorrect fixed plurals, and three loading labels use proper ellipses.
- URI construction uses the existing AndroidX `toUri` extension in the OsmAnd install action, geofence transition intent and Places content-provider contract. Removed an always-true geofence SDK branch while preserving the same PendingIntent flags on every supported API.
- Kept the production WorkManager initializer removal unchanged. A test-owned manifest removes the Startup provider from the reduced host-test manifest, avoiding an unmatched production removal marker and invalid empty metadata in Robolectric. The first selector-based experiment failed all three tests during manifest parsing; the failure is retained in SQLite46, and the corrected three-test run passes in47.
- New English resource strings/plurals are explicitly non-translatable, resolving CI MissingTranslation without adding Italian text or disabling lint.
- Removed every Italian translation resource introduced by this goal. New UI resources remain English. Unrelated pre-existing translations and the explicitly requested update prompt are preserved.

## Data, tooling and safety

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

The reviewed AndroidX cleanup converts ordinary asynchronous preference edits in Home, Workflowy, Timer counters/snapshots/widgets, Notifications status, Finance settings, Substances preferences/widgets and Git synchronization status. The same keys, values, batching and asynchronous apply behavior are retained. Widget deletion still clears every requested ID in one editor. Receipt consent/aliases, profile switching, the open manifest and recovery/version markers retain checked synchronous commits with explicit lint explanations; no commit/rollback/validation statement in the seven reviewed critical files changed. Finance bitmap creation/scaling uses the equivalent existing AndroidX defaults; drawing rotation now restores the canvas in a finally block. No dependency version, permission, Room schema or physical-device guard changes are part of this cleanup. Compilation passes; all21 focused host cases pass (SQLite56–62), and full unfiltered lint/architecture passes with0 errors and26 available-version suggestions.

Native QA history includes passing live search/tag-filter lifecycle, all eight overlapping candidates with cancel/explicit eighth selection/current visit/checkout, Where was I, all compact actions, twelve History scopes, real notification capture into shared History, Countdown, permission guidance, real external OsmAnd and Explore detail return. Failed attempts remain historical; later PASS is linked by scenario/artifact provenance rather than rewriting old records.

The installed primary Pixel app is still version69/schema24. The canonical emulator is70/schema25 with the first failed candidate and its update still unacknowledged. Source NV is now 70. The integrated final minified/signed artifact, validated primary migration/rollback, exact update/Audit/History proof, publication, original-inventory dispositions and operational Git purification remain pending.

Passive archive metadata readback found actual Telegram/WhatsApp events and no Signal/Google Messages events. No private message/title/sender/key was extracted. The original Notification Access setting is currently disabled and was preserved. This is coverage evidence, not invented incoming-message acceptance.

## Changed project files

Complete source diff against original canonical base `e031f296d0ac8535c9248b262f777b792ab99548`, refreshed at each checkpoint. Exact removed app-string inventory: `~/.local/share/personalhub-qa/removed-unused-resources-20261019.json`.


- `.codex/CODE_MAP.tsv`
- `.github/workflows/android-consumer-preflight.yml`
- `.github/workflows/android-instrumentation-ci.yml`
- `.github/workflows/android-unit-ci.yml`
- `.github/workflows/architecture-boundaries.yml`
- `.github/workflows/play-store-preflight.yml`
- `AGENTS.md`
- `NV.txt`
- `app/build.gradle.kts`
- `app/src/androidTest/java/com/gernalix/personalhub/CountdownQaDeviceTest.kt`
- `app/src/androidTest/java/com/gernalix/personalhub/HistoryAndPatchReviewDeviceTest.kt`
- `app/src/androidTest/java/com/gernalix/personalhub/HubHistorySearchQaDeviceTest.kt`
- `app/src/androidTest/java/com/gernalix/personalhub/NotificationsHistoryQaDeviceTest.kt`
- `app/src/androidTest/java/com/gernalix/personalhub/OsmAndExploreQaDeviceTest.kt`
- `app/src/androidTest/java/com/gernalix/personalhub/PlacesPixelQaDeviceTest.kt`
- `app/src/main/java/com/gernalix/personalhub/AppUpdateTracker.kt`
- `app/src/main/java/com/gernalix/personalhub/HubActivityPresentation.kt`
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
- `app/src/test/java/com/gernalix/personalhub/sincewhen/CounterElapsedTest.kt`
- `contracts/database/src/main/java/com/gernalix/personalhub/contracts/database/HubTagEntities.kt`
- `contracts/database/src/main/java/com/gernalix/personalhub/contracts/database/SinceWhenCounterEntity.kt`
- `core/alerts/src/main/java/com/gernalix/personalhub/core/alerts/AlertLinkPolicy.kt`
- `core/alerts/src/main/java/com/gernalix/personalhub/core/alerts/AlertNotificationDispatcher.kt`
- `core/alerts/src/main/java/com/gernalix/personalhub/core/alerts/HubAndroidPlatform.kt`
- `core/database/schemas/com.gernalix.personalhub.core.database.PersonalHubDatabase/25.json`
- `core/database/src/main/java/com/gernalix/personalhub/core/database/DatabaseGate.kt`
- `core/database/src/main/java/com/gernalix/personalhub/core/database/DatabaseProfiles.kt`
- `core/database/src/main/java/com/gernalix/personalhub/core/database/DatabaseStartupGate.kt`
- `core/database/src/main/java/com/gernalix/personalhub/core/database/DatabaseVault.kt`
- `core/database/src/main/java/com/gernalix/personalhub/core/database/PersonalHubDatabase.kt`
- `core/database/src/main/java/com/gernalix/personalhub/core/database/capsules/gitdata/GitDataFormat.kt`
- `core/database/src/main/java/com/gernalix/personalhub/core/database/capsules/gitdata/GitDataSettings.kt`
- `core/database/src/main/java/com/gernalix/personalhub/core/database/capsules/history/HistoryQueryCapsule.kt`
- `core/database/src/main/java/com/gernalix/personalhub/core/database/capsules/mutationevents/MutationEventCapture.kt`
- `core/database/src/main/res/values/strings.xml`
- `core/hub-context/src/main/java/com/gernalix/personalhub/core/hubcontext/HubContextExplorer.kt`
- `core/hub-context/src/main/java/com/gernalix/personalhub/core/hubcontext/HubContextLinks.kt`
- `core/hub-context/src/main/java/com/gernalix/personalhub/core/hubcontext/ResourceHubAdapter.kt`
- `core/hub-context/src/main/java/com/gernalix/personalhub/core/hubcontext/SharedTagEngine.kt`
- `core/hub-context/src/main/java/com/gernalix/personalhub/core/hubcontext/WorkflowyIntegration.kt`
- `core/hub-context/src/test/java/com/gernalix/personalhub/core/hubcontext/SharedTagEngineTest.kt`
- `core/ui/src/main/java/com/gernalix/personalhub/core/ui/ModuleHistoryHost.kt`
- `core/ui/src/main/res/values/countdown.xml`
- `core/ui/src/main/res/values/git_patch_review.xml`
- `core/ui/src/main/res/values/history_notifications.xml`
- `core/ui/src/main/res/values/history_query.xml`
- `core/ui/src/main/res/values/home_layout.xml`
- `core/ui/src/main/res/values/module_history.xml`
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
- `feature/multitimetracker/src/main/AndroidManifest.xml`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/MainActivity.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/MainViewModel.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/RandomTimerStore.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/TimeFenceNotifier.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/TimeFenceTimerScheduler.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/capsules/alerts/ui/AlertsScreen.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/capsules/now/ui/NowScreen.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/capsules/quickevents/ui/QuickEventsScreen.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/capsules/sincewhen/ui/LifePeriodsScreen.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/debug/DebugRecomposeCounter.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/hub/TimerSessionHubAdapter.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/persistence/CriticalDataGuard.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/persistence/SnapshotSqlite.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/persistence/SnapshotStore.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/persistence/SyncCountersStore.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/persistence/UiPrefsStore.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/ui/AppRoot.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/ui/components/AlertPopupHost.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/ui/components/PrecisionTimelineTrack.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/ui/components/SectionHeader.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/ui/components/SessionEditDialog.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/ui/components/SingleSubmitButtons.kt`
- `feature/multitimetracker/src/main/java/com/example/multitimetracker/ui/util/RewardToastManager.kt`
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
- `feature/notifications/src/main/java/com/gernalix/personalhub/notifications/capsules/archive/NotificationsActivity.kt`
- `feature/notifications/src/test/java/com/gernalix/personalhub/notifications/capsules/archive/ArchiveStoreTest.kt`
- `feature/soldi/src/debug/java/com/gernalix/personalhub/soldi/FinanceSemanticQaBridge.kt`
- `feature/soldi/src/main/java/com/gernalix/personalhub/soldi/BankingSecureStore.kt`
- `feature/soldi/src/main/java/com/gernalix/personalhub/soldi/FinancePhotoSemanticEngine.kt`
- `feature/soldi/src/main/java/com/gernalix/personalhub/soldi/FinanceReminderScheduler.kt`
- `feature/soldi/src/main/java/com/gernalix/personalhub/soldi/SoldiActivity.kt`
- `feature/soldi/src/main/java/com/gernalix/personalhub/soldi/SoldiEditorsUi.kt`
- `feature/soldi/src/main/java/com/gernalix/personalhub/soldi/SoldiUiModels.kt`
- `feature/soldi/src/main/java/com/gernalix/personalhub/soldi/receipt/ReceiptImport.kt`
- `feature/soldi/src/main/res/values-it/banking_strings.xml`
- `feature/soldi/src/main/res/values/banking_strings.xml`
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
- `feature/supercontacts/src/main/java/com/supercontacts/app/CallOverlayPermission.kt`
- `feature/supercontacts/src/main/java/com/supercontacts/app/CallSystemOverlayController.kt`
- `feature/supercontacts/src/main/java/com/supercontacts/app/MainActivity.kt`
- `feature/supercontacts/src/main/java/com/supercontacts/app/data/repository/ContactModels.kt`
- `feature/supercontacts/src/main/java/com/supercontacts/app/ui/app/SuperContactsApp.kt`
- `feature/supercontacts/src/main/res/values/strings.xml`
- `feature/wordpulse/src/main/java/com/wordpulse/app/MainActivity.kt`
- `gradle/libs.versions.toml`
- `project-capsule.yaml`
- `tools/android_connected_test_gate.py`
- `tools/android_paired_cutover_v24.py`
- `tools/android_pixel_apk.py`
- `tools/android_test_history.py`
- `tools/migrate_personalhub_v24_to_v25.py`
- `tools/run_ci_instrumentation.sh`
- `tools/test_android_connected_test_gate.py`
- `tools/test_android_paired_cutover_v24.py`
- `tools/test_android_pixel_apk.py`
- `tools/test_android_test_history.py`
- `tools/test_migrate_personalhub_v24_to_v25.py`
