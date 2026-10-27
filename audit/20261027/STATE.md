# PersonalHub deep audit — checkpoint 2026-10-27

Source: main c9aaa634dbe33fb1cb80609d137fd45c7ff01795.
Master protocol: MegaVault d187c0950d9e86473caa1dd7264d4bde7780d016.
Remote Desktop Commander usage exhausted: user Fedora/Pixel unreachable. Synthetic CI only.

- [x] Audit 998 Git files / 200332 textual lines (automated heuristics, **not** complete human reading).
- [x] SQLite schema v26: 100 tables, 240 indices, 2681 synthetic mutation attempts, integrity PASS.
- [x] Kotlin geo/search fuzz: 30000 distance + 10000 unicode; one confirmed antimeridian regression FAILED.
- [x] Deep-link fuzz: 4000 mutation samples, no failures.
- [x] Ephemeral Android 35 QA installation (package ending .qa) on CI only.
- [x] Android permissions: internal DatabaseActivity and PlacesProvider DENIED to shell UID.
- [x] 8 malformed external deep links; 0 attributable AndroidRuntime fatal messages.
- [x] Profile snapshots Home/Places/Timer/Soldi + 7.3 MB Perfetto trace saved in Actions artifact.
- [ ] Analyze Perfetto with official trace processor using pre-existing artifact (new lightweight workflow only).
- [ ] Deliver synthesized findings with scope limits, priority and links.

Initial run: https://github.com/gernalix/PersonalHub/actions/runs/40535233287
Original source unchanged, user DB untouched, main unchanged.

Next action: wait for **PersonalHub existing Perfetto trace analysis** workflow and read its log/artifact. Record measured PH startup durations and CPU section hotspots. Do not rerun whole Android audit or open additional PRs.
