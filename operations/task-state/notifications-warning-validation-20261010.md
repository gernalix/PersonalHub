# Notifications WARNING follow-up
Scope: complete Telegram/WhatsApp compatibility and longer power measurement; main app remains tested v64, no source feature change/rebuild/migration.
- [x] Dedicated task worktree, live Pixel 8a identity/package, installed v64 verified
- [x] Android performance skill; prior accepted production evidence reused
- [x] Telegram pre-test HTTP accepted, Fedora ID 77, explicit 90–110 minute availability confirmation and charger unplugged
- [ ] Metadata-only real Telegram and WhatsApp PHQA incoming tests
- [ ] 80-minute balanced ON(20) / OFF(40) / ON(20) measurement, physical battery counters and whole-process CPU/PSS/I/O
- [ ] Synthetic-only cleanup, original listener state restored, dual-channel end notices
- [ ] Sanitized evidence and documentation checkpoint commit/push, canonical integration
Privacy: no texts/titles/sender names, chat IDs, private database copies or content-bearing traces in Git. Only known PHQA-marker predicates, counts and structural flags leave metadata probe. Power-only Perfetto trace; no UI/text/log data sources. Battery stats preserved (no reset); no energy settings changes. Manual Notification Access switches only.
Method: disconnected from all charging sources; screen off/background; same synthetic notification load per 20-minute block; battery fuel-gauge and optional power-rail counters at 10s, cold-start/setup excluded; Android UID estimates separated from physical whole-device measurement. Temperature and counter resolution/confounders reported; do not force causal battery PASS if evidence cannot isolate it.
Next action: metadata probe baseline, then receive two synthetic PHQA messages per app. Cool down before power windows.

Progress: real WhatsApp PHQA incoming test observed 2 POSTED and 2 UPDATED snapshots, with structured cumulative message slots and 2 normalized PHQA messages in one conversation. Telegram real notification metadata, PHQA marker and normalized synthetic message observed; reconnection correctly OBSERVED. Only synthetic-marker-scoped aggregates will be published. First ON block: 1200.10 seconds recorded, first 2 minutes excluded from principal comparison; 43.12 CPU seconds over effective 1080.66 seconds, Android UID computed attribution 62.4 -> 63.2 mAh (whole block; not physical collector-only energy); final PSS 51433 KiB. User manually disabled Access for the 40-minute baseline. No reset of battery stats. Next action: complete OFF/OFF blocks, manual re-enable, final ON block; then power-trace analysis and private-marker-only compatibility report.
