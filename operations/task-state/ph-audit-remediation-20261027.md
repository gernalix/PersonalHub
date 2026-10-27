# PersonalHub audit remediation — operational state (2026-10-27)

## Objective
Fix reproducible functional defects and concrete performance/safety risks identified in audit/20261027/REPORT.md at source main c9aaa634dbe33fb1cb80609d137fd45c7ff01795. Minimal modifications; preserve personal database/schema and final-distribution version.

## Constraints
- No access to user's Fedora/Pixel via Remote Desktop Commander: monthly tool quota exhausted. GitHub connector + GitHub Actions are available.
- Strict isolation: branch `fix/20261027-ph-audit-remediation`; no direct changes to production SQLite, no unverified Pixel install, no NV increment unless final signed APK delivered.
- Current MetaInfrastructure verified MegaVault master d187c0950d9e86473caa1dd7264d4bde7780d016; PH AGENTS.md and ai/personalhubdoc.md read. Existing repository helper repo_single_writer.py and mv-git-finalize unavailable on Fedora; use compare+expected_sha Git leases and state exact limitations.
- Do not treat static risks as confirmed runtime defects. Test each change on CI before merging.

## Checklist
- [x] Reverify PH/MV remote source and code map; create isolated branch
- [ ] P1: ensure Timer first-frame timeout never skips profile/backup/Git/Workflowy boot tasks; targeted test
- [ ] P1: preserve alias/link creation timestamps in Luoghi; targeted tests
- [ ] P2: antimeridian bounding-box and nearest completeness; preserve failing fuzz regression
- [ ] P2: image inference CPU dispatcher, safe decoded pixel limit; tests
- [ ] P2: geometadata error handling that never permanently blocks subsequent places; tests
- [ ] P2: temporal UI state, banking page progress guards; tests
- [ ] Evaluate performance-only risks (autoexport, Luoghi recomputation) against evidence; change only when validated safe
- [ ] Consumer preflight before CI Gradle on changed APIs; architecture gate if boundaries affected
- [ ] Compile + targeted unit tests + CI emulator isolated; preserve output as evidence
- [ ] Review diff, merge only after required gates; do not bump NV/deliver APK without complete final device QA
- [ ] Recheck branch/main remote, request Git finalize only if Fedora access available; report exactly

## Current
Created dedicated remediation branch from c9aaa634. No product code changed yet.

## Next action
Inspect minimal code of the above components with GitHub connector, apply verified patches preserving public interfaces, push checkpoint, then run isolated CI via needed PR.
