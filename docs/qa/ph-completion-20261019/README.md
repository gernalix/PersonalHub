# PH completion work

Owner: this chat, native goal 01a15107-bdeb-7af2-9c60-adc69c6c182b. One operational writer.
Working tree: task/ph-completion-20261019, created with repo-task. No changes to the shared checkout or user devices.

## Required final acceptance

- [ ] OsmAnd bridge, verified official API, temporary PH-owned points/layer and distinct reference; replace the existing embedded map renderer completely.
- [ ] Esplora: saved/temporary reference, autocomplete, stable geographical distance ordering, missing coordinates, optional OsmAnd, return state, no silent writes.
- [ ] Countdown using Since When engine/editor, negative countdown and independent Timer/Since When/Countdown tags.
- [ ] Extended/compact Home switch, all compact icon tiles visible without scrolling on the target.
- [ ] Every new final Pixel app delivery increments NV exactly once; update prompt "app aggiornata alla vX", OK acknowledgement, one truthful update event visible in Audit and History.
- [ ] Places live search (name/address/notes/tags), preserving tag filters and sorting.
- [ ] Explicit, cancellable check-in choice for ALL overlapping places, no automatic nearest choice or hidden candidate limit.
- [ ] Where was I available on Places Home, correct incomplete/overlapping visit handling and timestamp lookup.
- [ ] Audit/debug the full Places tag lifecycle and consumers, with targeted persistence/filtering/namespace evidence.
- [ ] Explain/test the four Places sort criteria and visible ordering/direction.
- [ ] Internal History tab in each module, same global engine/UI with fixed module scope.
- [ ] Global History module checkbox menu remains open across changes until dismissed/OK.
- [ ] Inventory residuals: Project Capsule docs/issue68, stale issues69/71/91, historical goal/status discrepancies and superseded tasks/findings; preserve archives.
- [ ] ADB P272/P273/P274 residual environment tests, autonomous safe recovery where available; genuine physical USB/first-unlock limitation recorded if encountered.
- [ ] Notifications remaining coverage/energy warning and Workflowy focus limitation dispositioned truthfully without repeating completed acceptance.
- [ ] Targeted host/architecture/emulator QA, remote integration, immutable minified/signed final APK, exact-artifact Pixel install and publication.
- [ ] Mandatory verified Fedora+Telegram pre/post notices for exclusive Pixel interaction; preserve user data, backups, secrets and other device state.

## Current checkpoint

Implemented locally, pending compile/tests/device acceptance:

- Overlapping check-in always requires a choice, all candidates retained. Modal selection reuses existing journal and selection handler; cancel reuses the existing cancellation path.
- Fixed the navigation effect that incorrectly opened the new-place editor while awaiting an ambiguous choice.
- Places live search uses existing HubSearchField and accent/case-insensitive AND terms across meaningful fields/tag names. Archived/deleted tag filters no longer strand the visible list.
- Moved existing WhereWasIDialog from Visits to Home. Query no longer treats an old incomplete visit as permanent or predicts future occupancy; conflicting recorded locations are explicit.
- Added an explanation of the existing sort criteria.
- History module selection no longer closes after each checkbox; explicit OK added.
- Updated focused tests for the requested overlap semantics and added search/incomplete/overlapping-history regressions.

Consumer preflight run for HomeUiState, HomeScreen, CheckInPanel, WhereWasIDialog, choosePlace, updatePlaceQuery, WhereWasIResult, WhereWasIQuery, PlaceSearch. Returned consumers inspected; same-named Sostanze HomeScreen is a separate private function.

First compile started exactly once: :app:compileDebugKotlin --quiet --console=plain --max-workers=2. Execution session 18882; log /tmp/ph-completion-compile.log. Poll the same handle; do not duplicate a running build.

OsmAnd research: official osmand-api-demo and AIDL helper confirm V2 bound service, add/update/remove map layer, colored map points and setMapLocation. Official API AAR fetched to /tmp/osmand-aidl-api.aar (265243 bytes), SHA256 22fd19e905d1f7b0636f70044ea86d6c3caefd994280733d83638947498c3051. Not added to repository/dependencies yet. Source: https://builder.osmand.net/ivy/net.osmand/android-aidl-lib/master-snapshot/android-aidl-lib-master-snapshot.aar ; demo: https://github.com/osmandapp/osmand-api-demo . Decide reproducible API packaging before implementation; do not embed OsmAnd SDK or persistent favorites.

Next: collect compile result, fix only demonstrated leaf failures, run Places targeted tests, then continue full goal. NV still 69. No Pixel interaction/install/delivery has occurred.

## Verified host checkpoint

First app compile PASS; Places targeted suites 21 tests, 0 failures/errors. Shared internal module History host wired into the six feature roots, Notifications and Since When; app compile and checkArchitectureBoundaries PASS. App update observer/semantic event and acknowledgement dialog compile PASS. Further Places tag transaction/global-identity fixes compile PASS; their dedicated lifecycle tests and the shared Audit/History update-event test are running under execution 63423, log /tmp/ph-completion-tags-update-tests.log. No runtime UI acceptance, Pixel installation, NV change or final delivery yet.

Dedicated tag lifecycle tests: 3 PASS (namespace/global round trip, rename/archive/restore/bulk, rollback on failed tag write). Installed-version shared Audit/History persistence and idempotency test: 1 PASS. Corresponding app compile PASS. These are host results; UI and real-device acceptance remain open. User additionally confirmed emulator/Pixel/TCL availability; canonical emulator start/wait/stop and conditional Pixel notices remain required.

## OsmAnd and Explore checkpoint

Removed osmdroid renderer and dependency. Kept MapViewerActivity as compatibility entry point, now using official OsmAnd V2 API classes pinned as a small JAR with matching sources/license. PH-only temporary layer is removed before republishing (upstream update merges old point IDs) and on return/cancel. Reference orange, other PH points teal. Geo intent centers a cold-started external map; arbitrary reference uses no GPS. Missing app/error UI retains PH search/list. No favorites or durable catalog writes. Old custom clustering types removed; consumer forbid gate PASS. First OsmAnd compile + architecture PASS.

Explore locally implemented: existing autocomplete source; saved or temporary reference; all active places ordered by straight-line distance and deterministic ties; missing coordinates separate; no reference writes; detail/back navigation saves Explore state. Current app compile execution 72955, log /tmp/ph-completion-explore-compile.log. Native acceptance is still pending.

## Native QA checkpoint

App compile after Explore coordinate guards PASS. OsmAndMapPlanTest and PlacesProximityTest: 4 tests PASS, including invalid/missing coordinates, stable ties and 1501 places without truncation.

Home compact layout uses square icon-only tiles in four columns, accessible names and a persisted preference. App compile PASS. On canonical emulator Pixel_8a, isolated com.gernalix.personalhub.qa (NV69 QA only), HubHistorySearchQaDeviceTest selected methods: 3 PASS (all compact Home actions visible without scrolling, persisted toggle, History popup repeated selections plus undo, immutable module scope). Log /tmp/ph-completion-history-emulator.log. No Pixel installation or final delivery.

OsmAnd 5.4.7 from its official free-release APK installed only in the emulator. Initial real bridge attempt correctly refused API access: upstream log shows addMapLayer enabled=false. Enabled PersonalHub Test through OsmAnd Menu/Plugins, using observed UI. Added actionable permission guidance and a map-access button, with request-permission intent support when provided by the installed OsmAnd version and normal-launch fallback for 5.4.7. Authorized native rerun execution 26980, log /tmp/ph-completion-osmand-authorized-emulator.log; access-UI compile execution 84790. Do not run android_ui_summary during UiAutomator instrumentation: Android permits only one UiAutomation owner; the incidental shell dump failed with already-registered UiAutomation, not an app crash.

Project Capsule text corrected for active repo-task workflow and Room24; schema format remains historical v1 for compatibility. Its freshness test correctly fails while watched changes are uncommitted. Refresh reviewed_commit against the completed code checkpoint and repeat FAST/FULL before integration/issue closure.

Real OsmAnd bridge and return acceptance: PASS on 5.4.7, isolated emulator QA. Final native log /tmp/ph-completion-osmand-focus-emulator.log: OK (1 test). Live upstream readback confirms addMapLayer, three addMapPoint calls, showMapPoint and removeMapLayer on PH return. The initial geo intent retained an overly wide zoom; replaced with official showMapPoint, which upstream persists before bringing its map forward (cold-start safe). App compile PASS. No hard cap in the global marker repository; reuse listPlacesBlocking and retain explicit UUID chunking. Return uses PH task/Recents because the external app owns its own existing Back stack. Map access is explicitly enabled in OsmAnd Plugins; no credential or favorite writes. Screenshot /tmp/ph-osmand-reference-focus.png. Emulator stop requested through facade after this QA session; no physical device touched.
