# Notifications archive

Notifications is a feature capsule with its own private `noBackupFilesDir/notifications.sqlite`.
This intentional exception to the main database ownership rule is required by the notification goal:
notification contents never enter PersonalHub Git Data, Datasette, sync journal, main DB or automatic backup.
Raw events are append-only, enforced by SQLite triggers; active keys are disposable state.
Search is FTS4 with bound quoted tokens; app/date and key lookups are indexed. Queries are bounded.
UTC instants are persisted, local dates are converted at device midnight including DST.

The Android-owned NotificationListenerService has no polling, periodic job, foreground service or wakelock.
Callbacks detach serializable text/MessagingStyle snapshots to a bounded ordered asynchronous writer.
Storage/queue/capture failures set a visible non-content error indicator. Callback payloads are never logged.
Unpersisted callbacks can be lost on process death; neither Android nor this module guarantees complete history.
Reconnect snapshots are OBSERVED, not invented POSTED/REMOVED history. Notification removal does not imply message deletion.
MessagingStyle sender/key/URI, timestamps, historic messages and attachment metadata are preserved; attachment content is never fetched.
No app-specific parsing or accessibility/web scraping is used. Sensitive content, other profiles and notification gaps can be hidden by Android.

The user must enable Notification access in Android settings. Revocation stops collection, leaving existing history readable.
The screen is secure against screenshots. Export uses a coherent validated VACUUM INTO snapshot under private storage,
then writes only to Android local external-storage/downloads document providers. Cloud providers are rejected.
Export is explicit; unencrypted local backups contain private contents and must be protected by the user.
No restore or remote transport is introduced. The archive is device-wide and independent of main DB profiles.

Synthetic acceptance: ArchiveStoreTest checks posted/updated/removed classification, immutable old snapshots,
reconnection semantics, persisted reopen, structured/missing fields, FTS/app/date/DST boundaries and coherent export.

## Database decision after 2026-10-09 steer
Verified actual sources: GitDataFormat.exportPending takes DatabaseVault.backupCurrent and exports
the tracked table shards; GitDataTracking.tables derives from SyncJournal.tables, which selects
SQLite tables except an operational denylist. Git history persists before/after payloads,
and Datasette uses that same table inventory. SAF backup is a coherent whole main DB copy.
Therefore the existing main DB DOES have Git data versioning/remote replica paths.
Keep the already implemented separate archive to structurally exclude private notifications,
messages, identities and mappings from all those paths, without altering existing backup/import,
Room schema validation or remote publication. Volume is not the reason. No migration of
PersonalHub data occurs. People mapping uses public IDs scoped by database profile ID,
resolved read-only through the existing contact contract; no name-based merging.
