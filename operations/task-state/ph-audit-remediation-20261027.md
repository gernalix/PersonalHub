# PersonalHub audit remediation — checkpoint 2026-10-27

## Goal
Fix all confirmed defects and safely address actionable risks in audit/20261027/REPORT.md, based on main c9aaa634dbe33fb1cb80609d137fd45c7ff01795. No schema migrations, no NV bump or final APK without physical-device QA.

## Verified
MegaVault master d187c0950d9e86473caa1dd7264d4bde7780d016; PH operating rules; CODE_MAP relevant paths. GitHub API is usable. Remote Desktop Commander monthly quota exhausted; cannot access local Fedora or Pixel, so developer-specific single-writer wrapper and mv-git-finalize are BLOCKED.

## Checklist
- [x] Create independent Git branch and persist state
- [x] Startup service initialization continues after Timer 15s first usable frame timeout
- [x] Preserve existing createdAt on Luoghi alias/link update
- [x] Wrap longitude bounding boxes across antimeridian; Room SQL and regression
- [x] Page all nearest/nearby candidates to avoid 20k alphabetic cutoff
- [x] Allow failed individual reverse-geocode calls to be skipped within run; retry systemic failures
- [x] Reject repeated/decreasing/>100 bank pagination cursor
- [x] Bound bitmap dimensions/pixels, crop square before image resize
- [x] Move ONNX text/image embedding, gallery search and indexing off UI dispatcher
- [x] Limit temporal result snapshot state; re-query on recreation; semaphore bounds linked-people reads
- [ ] Consumer preflight before first Gradle run
- [ ] Compile + targeted JVM tests + architecture gate on isolated GitHub runner
- [ ] Fix only errors evidenced by compiler/test, then run isolated Android QA as needed
- [ ] Review performance-only risks (frequent autoexport and global Luoghi recomputation) without changing durability/unmeasured behavior
- [ ] Decide integration/merge into main after tests and final source inspection
- [ ] Git finalization on Fedora if available; otherwise report blocker

## Safety
Production database and Pixel untouched; all edits confined to fix/20261027-ph-audit-remediation. No release signing or APK delivery. No PR created yet. Main remains c9aaa634 until explicit integration.

## Current action
Run preflight, compile and unit tests with a dedicated one-off workflow (GitHub Actions), then inspect logs. Patch compiler/test findings only.

## Next action
Inspect initial `PersonalHub audit remediation validation` Actions run. Verify preflight listed both new PlaceDao methods and top consumers; inspect any failed `:feature:luoghi`, `:feature:soldi`, `:app` test. If PASS, run CI instrumentation on isolated emulator and consider one PR for merge, not for documentation checkpoints.
