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
