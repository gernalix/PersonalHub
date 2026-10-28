# PH runtime and security validation (audit branch)

Source PH `47fd585bb01a630c2390a2ee511dd7d6feae1a13`. User requested real Pixel validation, measured export/startup, and further dynamic security tests.

## Safety and limitations
- The user's Pixel 8a is **NOT reachable**: monthly Remote Desktop Commander quota was exhausted today, and neither a secure alternative ADB connector nor required pre/post Fedora and Telegram notification flow can be verified.
- The only permitted Android runtime target in this branch is **one ephemeral Android API 35 emulator** with `com.gernalix.personalhub.qa` package identity. Guard rejects real hardware and any production package before mutation.
- The benchmark inserts a **synthetic** 48 MiB BLOB in the isolated QA DB to mimic the database's photo content. It tests the real `DatabaseVault.exportNow` snapshot/staging/twice-readback/hash path with a local-disk simulated publisher **not actual SAF**. Results cannot be generalized to real provider/Pixel battery or latency.
- No production APK, signing keys, real banking credentials, personal photos or databases are used. No main branch merge needed for one-time diagnostics.

## Checklist
- [x] Verify live PH and MegaVault source, CODE_MAP and existing CI PASS.
- [x] Check alternative remote plugin availability; none identified as ADB backend.
- [x] Prepare isolated test harness for cold startup, 2/16/48MiB export, 25 rapid writes, Perfetto CPU/UI, private component probes and 32 malformed deep links.
- [ ] Run dedicated Actions job (triggered by commit) and inspect exact failed/passed steps.
- [ ] Collect startup medians, export full-path elapsed times, WorkManager REPLACE request count, frame durations, CPU sections, security outcome.
- [ ] Publish evidence-based audit summary for user; do not overstate Pixel coverage.
- [ ] Fedora `mv-git-finalize --project personalhub` not possible until authorized host access returns; be explicit.

## Next action
Read [GitHub Actions for this branch](https://github.com/gernalix/PersonalHub/actions/workflows/ph-runtime-qa-20261027.yml), inspect artifacts named `personalhub-runtime-20261027`. Any failure should be resolved by editing *only diagnostic harness* unless reproducible evidence demonstrates a product defect. Do not rerun the entire green instrumentation CI suite.
