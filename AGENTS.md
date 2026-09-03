# PersonalHub operating rules

- Follow the governing MegaVault protocol before project work.
- `version.txt` is the sole source of truth for both Android `versionCode` and `versionName`. Every future PersonalHub development prompt increments its integer exactly once by `+1`; never derive it from time, Git, or generated source.
- The Home title is always `PersonalHub`; render only the version number, small and discreet, at the bottom-right.
- The final debug APK must be named exactly `<version>.apk` (for example `3.apk`) with no other prefix or suffix.
- Keep the single `personalhub.db`, atomic validated import/export, and crash rollback. Never reintroduce `MigrationActivity`, `:core:migration`, legacy feature databases, or migration-map code.
- Pre-import backups may be removed only when no valid import-pending marker references them. Successful verified imports and startup recovery must clean obsolete pre-import backups.
