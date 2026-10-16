# P235 historical worktree reconciliation

This Git-only task produces no APK, changes no application NV and never executes
bank or Workflowy requests. The user confirmed that no other chat was running
before the residual worktrees were reviewed.

## Alert implementation

The detached September 27 commit `6f194a9d` proposed a host-owned
`HubAlertsActivity`, `ManagedAlertProvider` and per-feature API adapters. Its
four dirty Kotlin files and `ManagedAlertsTest.kt` belong to that same design.
The later, integrated commit `a1c54798` instead owns all entry routing in
`UnifiedAlertsRoute` and the actual shared editor in Timer's feature capsule.
`UnifiedPlaceAlertsProvider` is the thin Places persistence adapter. Reintroducing
the old host Activity and adapters would create a second competing editor and
undo the current feature-owned architecture.

The old draft validation is also superseded: Places already validates messages,
trigger types and exact-place/tag targets in `PlaceAlertRepository`. Timer's
current representation supports always-scoped rules without tags, including the
existing random-alert QA fixtures; the stale draft's unconditional nonempty-tag
restriction must not replace it.

The recovered commit and exact dirty originals are preserved in a private Git
bundle, compressed archive and the complete original checkout directory before
its obsolete registration is retired. They are retained historical source, not
unfinished application work or an instruction to restore the old topology.

## Workflowy QA

`WorkflowyAlertTapQaDeviceTest.kt` is a stale combined manual QA fixture, not a
missing production feature. Its Places/Timer notification taps and enabled/off
fallback are covered by the integrated `WorkflowyNotificationTapQaDeviceTest`;
its random-alert restart case has the integrated `WorkflowyRandomRestartQaDeviceTest`.
The recovered source creates real remote nodes and depends on private device
fixtures. It is preserved privately rather than added as a duplicate runnable
suite. Neither fixture nor remote API is executed during purification.

## Retention and verification

Every retired non-C3 checkout requires an exact HEAD, canonical SHA, index and
whole-worktree fingerprint review. The existing single writer verifies all
archived files, Git bundle and original preservation before removing only the
obsolete Git registration. Ignored APKs, BLOB/database files, symlinks and other
outputs are preserved, not treated as disposable build waste. Explicit C3
roadmap worktrees stay in place, Git-clean and with their HEAD already integrated.
Patch-equivalent historical commits are retained with their exact original Git
history in the private bundle.

Focused verification uses the current `:core:alerts:testDebugUnitTest` suite.
GitHub checks and integration are performed by the existing single writer; no
manual protected-main merge, history rewrite or force-push is used.
