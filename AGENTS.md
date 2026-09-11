# PersonalHub operating rules

- Follow the governing MegaVault protocol before project work.
- For code localization, search `.codex/CODE_MAP.tsv` first using the user's feature/concept/alias and open only the matched primary/related paths. Treat the map as routing hints, not source of truth. Use narrow symbol/path search next and repo-wide search only when the indexed targets are missing, stale, or insufficient.
- Keep `.codex/CODE_MAP.tsv` compact and semantic: update only rows whose feature ownership, important symbol, path, or targeted test materially changed; never turn it into an exhaustive class/function inventory.
- `version.txt` is the sole source of truth for both Android `versionCode` and `versionName`. Every future PersonalHub development prompt increments its integer exactly once by `+1`; never derive it from time, Git, or generated source.
- The Home title is always `PersonalHub`; render only the version number, small and discreet, at the bottom-right.
- The final debug APK must be named exactly `<version>.apk` (for example `3.apk`) with no other prefix or suffix.
- Keep the single `personalhub.db`, atomic validated import/export, and crash rollback. Never reintroduce `MigrationActivity`, `:core:migration`, legacy feature databases, or migration-map code.
- Pre-import backups may be removed only when no valid import-pending marker references them. Successful verified imports and startup recovery must clean obsolete pre-import backups.
- Before the first final APK/build/delivery for a change that adds or alters UI, perform one focused source/UI gate for newly exposed values: raw epoch milliseconds, database IDs/UUIDs, cursors, and backend-only encodings must never be user-visible unless the feature explicitly requires them.
- For terminal Git writes in any repository required by a PersonalHub task, resolve the repository's current canonical/default branch from remote state and push that branch; never assume or hardcode `main` versus `master`.
