# Notifications and Unified Conversations
- [x] Phase 1: collector, private immutable SQLite, archive UI, backup, synthetic acceptance
- [x] Phase 1: PASS, commit and push
- [ ] Phase 2: normalization, reversible People mapping, UI, synthetic acceptance
- [ ] Phase 2: PASS, commit and push
Evidence: remote Telegram readiness 2026-09-19 newer than notifier 2026-09-12/config 2026-08-04; reused. Base version 63; no release bump requested.
DB decision updated after steer: keep separate archive. Verified GitDataFormat.exportPending, GitDataTracking.tables and SyncJournal.tables: main data is versioned in Git and replicated; dedicated archive prevents private raw/normalized content entering these paths. No volume justification or unnecessary migration.
Main DB unchanged; no C2/C3 dispatch. Dedicated task worktree.
No invasive Pixel operations before requested warnings and availability confirmation.
Blocker: none.
Phase 1 PASS: 4 synthetic Robolectric/native SQLite tests, zero failures; app consumer compile PASS; feature lint PASS; architecture boundaries PASS; diff whitespace PASS. No runtime polling/periodic job/foreground service/wakelock. No Pixel/emulator operation. Android QA skill not applicable to host synthetic tests; no device/performance claim.
Resolved: platform MessagingStyle extraction uses AndroidX compat; API-30 conversation metadata guarded; private export snapshot opened writable for FTS integrity verification; Compose strings use stringResource.
Checkpoint: commit and push this phase before phase 2 (commit identity in Git history).
Next action: incremental conversations, identities and reversible People mappings, then synthetic acceptance.
