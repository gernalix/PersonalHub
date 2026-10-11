# External database schema upgrades

PersonalHub does not ship historical Room migrations inside the APK. A data-bearing device must be moved to the schema required by the APK before database-backed screens run.

## Runtime gate

DatabaseStartupGate runs before the Home UI and before every module Activity initializes feature code. A mismatched SQLite user_version produces a blocking compatibility screen instead of allowing Room-backed code to crash.

DatabaseVault.ensureStartupReady remains the structural validator. The gate is not a migration engine.

## Schema 20 to 23

The historical endpoint of this path is Room schema 23; the current schema is 24. Schema 21 removes the Salute domain from schema 20. Schema 22 adds the two Since When tables, and schema 23 adds the two finance photo/owned-item tables; the retained tables are unchanged.

Use:

- tools/migrations/personalhub_20_21.sql
- tools/migrate_personalhub_v20_to_v21.py
- tools/test_migrate_personalhub_v20_to_v21.py

The runner refuses non-v20 or wrong Room-identity sources, uses SQLite backup so WAL/SHM state is included, never mutates the source, suspends copied runtime triggers, removes Salute data/schema, writes user_version 21 plus the schema-21 Room identity, validates the complete current Room table/foreign-key/index contract, runs quick_check and foreign_key_check, and verifies hashes for every non-Salute table not intentionally touched.

Example:

    python3 tools/migrate_personalhub_v20_to_v21.py personalhub-v20.db --output personalhub-v21.db

Replace the live device database only after the JSON validation report says PASS. Preserve the original database and sidecars until v60 has passed runtime smoke testing.

For a schema 21 or 22 source, run `python3 tools/migrate_personalhub_v21_to_v23.py SOURCE.db --output TARGET.db`. For schema 20, first run the 20-to-21 runner on a copy and then run the 21-to-23 runner on its output. The second runner validates the source Room identity and table shapes, makes a SQLite snapshot including WAL state, adds only the four required tables, and requires `quick_check`, `integrity_check`, `foreign_key_check`, and exact hashes and counts of all existing table rows to pass. A schema 23 source is also copied and validated without a schema change.

An APK build/install is not a database migration. Never substitute uninstall, clear-data, destructive Room fallback, or a user_version-only edit.

## Schema 23 to 24 — canonical identities (925612)

Use `tools/migrate_personalhub_v23_to_v24.py`. Its output is prepared and validated outside Android; the APK contains no historical Room migration. See [CANONICAL_IDENTITIES.md](CANONICAL_IDENTITIES.md) for the classification and [B8_HANDOFF_193828.md](B8_HANDOFF_193828.md) for the deployment and rollback procedure. Schema 24 requires Room identity `f5ff5327fbc3067a659ded92d66db243`.

The schema-23 database and previous APK remain usable before cutover. The new APK refuses schema 23 through DatabaseStartupGate; the previous APK cannot open schema 24. Compatibility means an explicit paired APK/database cutover and retained schema-23 backup, not simultaneous writes by different Room versions.

### Reusable paired Pixel cutover gate

For a **future** device still at schema 23, `tools/android_paired_cutover_v24.py` combines the previously manual safe steps in one guarded command: verify the Pixel and final APK, verify *both* notification receipts, disable the app to freeze writes, back up the installed APK plus SQLite DB/WAL/SHM, produce a coherent SQLite snapshot, migrate and validate v24, compare the frozen source checksums again, stage and verify the target, retain the original DB/sidecars for rollback, and install exactly the validated APK. On failure after replacement, it attempts paired rollback to the previous APK and database; ambiguous recovery leaves the app disabled. Multiple profiles or stale sources are refused. It never clears app data.

Use only inside the already-authorized **exclusive** Pixel window, after the new APK passed isolated tests. The explicit `--execute --allow-production` switches are both required. Keep the private `--output-dir` outside Git. Do not log or commit its contents.

```bash
python3 tools/android_paired_cutover_v24.py \
  --serial <verified-pixel-serial> \
  --new-apk /private/path/final-tested.apk \
  --output-dir /private/new-cutover-directory \
  --pre-notification /private/verified-pre-notifications.json \
  --execute --allow-production
```

The helper returns `PAIR_INSTALLED_QA_PENDING`, **not** a release PASS. A live app/History/Timer smoke test, data/ID and Git-export readback, verified post-test Fedora+Telegram notifications, and the normal immutable-APK delivery gate are still required. If those checks fail after activation, preserve post-cutover edits and reconcile them before a manual paired rollback. Never run this v23→v24 helper on a device already at schema 24. Host-only safety tests: `PYTHONDONTWRITEBYTECODE=1 python3 tools/test_android_paired_cutover_v24.py`.
