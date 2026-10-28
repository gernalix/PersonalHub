# PersonalHub audit remediation — checkpoint 2026-10-27

## Objective
Remediate verified bugs and safe high-impact performance/safety risks from `audit/20261027/REPORT.md`. Source verified main c9aaa634dbe33fb1cb80609d137fd45c7ff01795; cross-project authority MegaVault master d187c0950d9e86473caa1dd7264d4bde7780d016.

## Constraints
Keep single `personalhub.db` and current Room schema v26; no destructive import or changes to real data. No NV bump/final APK without final minified/signed device QA. Fedora Remote Desktop Commander monthly quota exhausted: cannot use Fedora single-writer or mv-git-finalize and cannot certify Pixel. Use dedicated GitHub branch with expected-SHA ref leases.

## Completed source fixes
- [x] Timer first-usable-screen 15s timeout cannot prevent profile restoration, backup, Git and Workflowy startup
- [x] Luoghi alias/link `createdAt` persisted through edit; new DAO read stays on IO
- [x] Geographic bounding box/SQL cross ±180° and invalid input guard
- [x] Nearby/nearest queries page all candidates instead of first 20k sorted alphabetically
- [x] Nominatim individual permanent HTTP 4xx skipped in current run; systemic rate limit/outage retries
- [x] Bank pagination detects backward/repeated/>100 pages; unit tests
- [x] Finance image decode bounds and safe center square before resize; ONNX on Default dispatcher; cancellation propagates
- [x] Temporal search avoids saving bulky results into Bundle, re-searches after recreation; bounded linked-people queries
- [x] Luoghi derives visits/stats independently of query UI state and on Default dispatcher
- [x] Soldi collection is lifecycle aware; 12 Flow collections
- [x] Regression tests for dateline/DAO, creation timestamps, page guard, image sample sizing

## Verification history
- First GitHub CI 40541341153 preflight PASS / app compile FAIL (removed encodeRef/decodeRef accidentally); corrected.
- Second GitHub CI 40542068395 preflight PASS / app compile PASS / 73 Luoghi tests, 2 FAIL due *new test harness* calling Room blocking DAO on main; JUnit XML verified, no product assertion failure. Both corrected to IO dispatcher.
- New GitHub CI run 40543594046 for latest product+test changes; verify compile, targeted tests and architecture gate. Do not conclude PASS from earlier run.

## Remaining checklist
- [ ] Inspect 40543594046; fix only targeted compiler/test failures and rerun once source changes
- [ ] Targeted Android QA instrumentation on ephemeral emulator if CI available and no Pixel access
- [ ] Confirm release path / PR merge or leave branch blocked transparently
- [ ] No change to HubAutoExport REPLACE until durability benchmark is available (existing recovered periodic backup, existing test contract)
- [ ] No final signed APK / physical Pixel delivery during this task
- [ ] Git finalize if Fedora accessible; otherwise declare explicit blocker

## Current
Working branch `fix/20261027-ph-audit-remediation` remote HEAD `42252ae8744e8d1c3911be8f52b1a4f0456be266`. GitHub CI run 40543594046 queued; prior validation source found only newly written test harness main-thread access violation. Main remains at original commit so far.

## Next action
Check `https://github.com/gernalix/PersonalHub/actions/runs/40543594046`. On PASS, create review PR to main for integrated CI; read unit, instrumentation, architecture and Play preflight outcomes; merge only if gates pass. No unnecessary reruns or unrelated changes.


## Combined source integration (2026-10-28)
The four remaining audit/remediation branches are combined in the guarded
`task/integrate-reviewed-audit-work-20261028` worktree. Preserve the newer
contracts/database ownership and unchanged NV/schema. The old antimeridian
fuzz assertion now uses the wrapped-longitude predicate introduced by the fix.
Consumer preflight passed for the added DAO/page and sampling APIs.
Local combined-source validation: app Kotlin compile, Android test Kotlin
compile and architecture boundary gate PASS; 76 Luoghi, 38 Soldi and 69 app
JUnit tests PASS (183 total). No physical device, application data, final APK,
service, notification or installation changed. Prior photo-fix PR #111 has
provider PASS for unit, instrumentation, architecture and Play preflight;
the new combined revision still requires its own provider integration gates.
