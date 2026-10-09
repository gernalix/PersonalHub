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

## Unified Conversations
Archive schema v2 adds conversations, identities, messages, message_sources, people_mappings
and a transactional last-event cursor. The v1 upgrade adds only derived tables; raw events
and their immutability triggers are retained. Capture commits raw data before normalization,
so a normalization failure cannot discard a captured notification. Processing is ordered in
batches of at most 100 and resumes from the cursor, not from the start of history.

Structured MessagingStyle is preferred. Self/null senders and aggregate summaries are not
normalized as received messages. Text fallback requires a message category, a stable thread,
a nonempty title/text and a single line; it remains LOW confidence with unknown sender.
Timestamp-less contents use capture time, explicitly labeled. Attachment bytes are not read.

Deduplication scopes sender/text/timestamp/attachment tuples to their original app/user/thread
and preserves multiplicity in each observed snapshot. Every repeat retains event/slot/historic
provenance. Identical texts with distinct observed timestamps or same-snapshot multiplicity
remain distinct. Without platform message IDs, indistinguishable repeats cannot be proven
as separate messages: their multiple observations remain raw/source-linked and uncertainty
is shown. No absence from a newer snapshot asserts deletion. A same-sender/time content
variation is only a possible revision or distinct message, and both contents are preserved.

Shortcut/conversation IDs are scoped to app and Android user. Weak notification-key threads
are marked uncertain and split after an observed removal or reconnection gap. Name-only
senders are separate uncertain identities, not globally merged by name.

People associations use profile ID and stable public contact ID, validated through existing
read-only DAO contracts. Multiple conversations/apps and identities may link to one person;
multiple people may link to one thread. Links can be removed/replaced without altering raw
events or original threads. Missing/deleted People records are shown unavailable and can be
unlinked. No association is written to the Git-versioned main DB.

UI provides per-app thread lists, original-thread messages, person aggregate timelines,
text/app/person/local-period filters, sender/thread link editors and original notification
snapshots. Keyset pagination bounds timeline/thread queries to 100 rows, with indexed app,
time, signature, sender and mapping lookups. Provenance detail shows the latest 100 source
snapshots; every older source event remains in the raw searchable timeline.

Validation uses synthetic Robolectric/native SQLite only. No emulator was started, no Pixel
UI was controlled, no private device data was read, and no final distributable APK was built.
The test-android-apps device/performance workflows are not applicable to these host tests.
No resource measurements or device E2E result is claimed. Enable Notification access manually
after installing an integrated build; Android may omit/redact contents or disconnect collection.
