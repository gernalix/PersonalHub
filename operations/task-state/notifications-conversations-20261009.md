# Notifications and Unified Conversations
- [x] Phase 1: collector, private immutable SQLite, archive UI, backup, synthetic acceptance
- [x] Phase 1: PASS, commit and push
- [x] Phase 2: normalization, reversible People mapping, UI, synthetic acceptance
- [x] Phase 2: PASS, commit and push
Evidence: remote Telegram readiness 2026-09-19 newer than notifier 2026-09-12/config 2026-08-04; reused. Base version 63; no release bump requested.
DB decision updated after steer: keep separate archive. Verified GitDataFormat.exportPending, GitDataTracking.tables and SyncJournal.tables: main data is versioned in Git and replicated; dedicated archive prevents private raw/normalized content entering these paths. No volume justification or unnecessary migration.
Main DB unchanged; no C2/C3 dispatch. Dedicated task worktree.
No invasive Pixel operations before requested warnings and availability confirmation.
Blocker: none.
Phase 1 PASS: 4 synthetic Robolectric/native SQLite tests, zero failures; app consumer compile PASS; feature lint PASS; architecture boundaries PASS; diff whitespace PASS. No runtime polling/periodic job/foreground service/wakelock. No Pixel/emulator operation. Android QA skill not applicable to host synthetic tests; no device/performance claim.
Resolved: platform MessagingStyle extraction uses AndroidX compat; API-30 conversation metadata guarded; private export snapshot opened writable for FTS integrity verification; Compose strings use stringResource.
Checkpoint: commit and push this phase before phase 2 (commit identity in Git history).
Phase 1 checkpoint: 53ff36ff pushed and independently verified at remote branch HEAD.
Phase 2 implemented: private incremental normalized schema v2; original event/slot provenance; observed multiplicity, group/homonym isolation, uncertain revision/partial/time labels; app/thread/person timeline and filters; reversible profile-scoped People associations. Keyset queries bounded to 100 rows.
Resolved: standalone export FTS validation; public/hidden Android API mismatches; API-30 guard; Compose resource calls; host translation convention; chronological pagination across clock changes. Git automatic pack-refs was rejected by canonical protection during commit housekeeping; task commit/push succeeded, canonical refs preserved. No infrastructure changes.
Phase 2 PASS: 19 synthetic tests, zero failures/errors/skips. Final app consumer compile PASS; feature/app lint PASS; architecture boundaries PASS; whitespace diff gate PASS. Includes v1->v2 archive upgrade preserving raw data and FTS; live service callback simulation with asynchronous ordered writes; bounded pagination; weak reconnection isolation; cumulative/identical/duplicate/group/homonym/revision/partial/out-of-order cases; multi-app/multi-person reversible profile mapping; reopen idempotence.
No private content enters main DB, Git Data, sync or code Git. Dedicated archive and explicit local backup are preserved after the database steer.
No final APK build, Pixel install, UI takeover, resource measurement or device E2E claim. Access permission requires manual enabling after installation; no blocking user action for synthetic acceptance.
Checkpoint: phase 2 commit + push (identity in task branch history); canonical integration via repo-task finish, never manual canonical merge.
Blocker: none for requested synthetic/code delivery.
Next action: canonical single-writer integration, then enable Notification access on an installed build.

## Production validation 2026-10-09
Same chat and task-state; new Git task worktree required because implementation task is terminal/merged. No C2/C3 operation or new PROMPT_ID.
- [x] PR #74 canonical integration: 96f080fbf1c46b10da83bf4664d8b13ce9ae49bf, all CI checks SUCCESS.
- [x] Pixel read-only identity/package preflight: Google Pixel 8a, Android 17, PH v63 installed; main DB user_version 23 matches code. No migration needed.
- [x] Installed signing certificate matches canonical shared signer. Shared Telegram readiness from implementation goal reused.
- [x] Final minified+signed debug v64 build and artifact verification
- [x] Dual-channel pre-test notice and explicit user availability confirmation
- [x] Reversible private coherent backup, compatible in-place install and manual Notification Access
- [x] Real device E2E, People mappings, restart/reconnection, local export/integrity
- [x] CPU/RAM/disk/background measurements; energy WARNING (short samples inconclusive)
- [x] Synthetic-only cleanup, stable Pixel state, dual-channel end notices
- [ ] Artifact Telegram delivery, evidence checkpoint commit/push and canonical integration
Build correction: debug lacked R8; opt-in -Ppersonalhub.minifiedDebug=true reuses existing release optimizer/proguard/signing rules without changing ordinary worker/test builds. Version incremented once from integrated 63 to 64.
Temporary synthetic fixture prepared outside Git under /tmp/ph-notifications-production-20261009; no device installation or private notification reads yet.
Next action: finish build and verify exact artifact, then request the required Pixel availability window.

Artifact frozen: v64, 211136022 bytes, SHA256 f50bc29029be550c9e3f597b960feb83ce1ff15eb1db2ac639993d63f61712cb; canonical/installed signer match and R8 mapping generated. Pre-test Telegram accepted, Fedora notification ID 44; user confirmed Pixel availability in this chat.
Next action: coherent private backup and in-place install, then baseline with collector disabled and manual grant.

Production functional/device acceptance PASS. Main DB before/after coherent backups: integrity ok, schema 23, 105 tables with no fewer rows. Frozen v64 installed via canonical helper; user manually enabled access. Live capture, normalized 4 messages/2 threads, UI FTS/app/date/person filters, provenance, reversible People link, reconnect OBSERVED, local SAF export all PASS. Owned temporary fixture and synthetic rows removed; nonfixture history preserved and final listener bound. No PH uninstall/clear-data or energy-setting change.
Resources: disabled load 1.81 CPU s / 30.25 s, PSS 153350 KiB; enabled load 3.96 CPU s / 30.22 s, PSS 135500 KiB; enabled idle 0.52 CPU s / 30.21 s, PSS 127741 KiB. Whole-process writes are confounded by ordinary main DB workers/exports; no collector energy percentage inferred. Energy WARNING, not PASS. Four real messaging apps installed but supplied no observed events in the test window; compatibility unproven, no universal acquisition claim. No PersonalHub fatal crash in available crash buffer.
Telegram end accepted and Fedora ID 50; Pixel returned Home, usable, listener connected. Private rollback evidence stays outside Git. Public evidence: operations/evidence/notifications-production-20261009.json.
Next action: verify exact APK GitHub asset/deliver link, then final evidence commit/push and canonical single-writer integration.

Frozen 64.apk published to existing private personalhub-dev-apk prerelease; GitHub asset digest and size independently match tested/installed artifact. Release link delivered using shared Telegram notifier (API accepted). Checkpoint build commit 4cb74d54 pushed. Next action: commit/push production evidence and repo-task finish; await canonical integration.
