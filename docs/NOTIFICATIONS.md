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

## Pixel production validation 2026-10-09
Integrated implementation PR #74 and installed minified, canonically signed v64 on Pixel 8a / Android 17 without migration or data reset. Manual Notification Access, real synthetic callbacks, cumulative normalization/dedup, structured multi-sender data, provenance, raw and normalized search, app/date/person filtering, reversible People mapping, process reopen/reconnect and local SAF export passed. Reconnect produced OBSERVED rather than invented historical events. Export and final archive integrity/FK checks passed. Owned synthetic data and fixture were removed while preserving nonfixture history; listener remained connected.
Whole-process CPU/PSS/I/O samples are recorded in operations/evidence/notifications-production-20261009.json; energy remains WARNING because short samples cannot isolate battery cost. Telegram/WhatsApp/Signal/Google Messages were installed but emitted no observed test-window events; their actual compatibility remains unverified. Existing host synthetic tests and canonical CI PASS were reused. Private DBs/backups and content remain excluded from Git.


## Real-app compatibility and long resource measurement 2026-10-10
Follow-up on unchanged production v64, using `test-android-apps:android-performance` and the physical Pixel. Telegram captured the authorized PHQA pre-test notice; WhatsApp captured the two user-arranged incoming PHQA test messages. Marker-only numeric/structural probes found respectively 1 and 2 normalized messages, one conversation per app, POSTED/UPDATED and subsequent OBSERVED reconnect snapshots. Normalized counts stayed unchanged across reconnects. Both apps lacked reliable sender key/URI in these observed messages: confidence remained MEDIUM, and WhatsApp's two uncertain identity rows are not evidence of two human senders. Compatibility PASS covers these observed received-notification cases, with Android redaction/gap limitations retained. No private text, title, sender name, key, URI or chat ID left the probe.

Completed ON20/OFF20/OFF20/ON20 minutes without charging or battery-stat resets. The user manually disabled/re-enabled Notification Access. The same ten silent cumulative fixture updates ran in each block. Screen was asleep with stock Dozing/AOD; two minutes per block were excluded, giving 72 effective minutes. Whole-process CPU and I/O came from proc counters; PSS from meminfo. Power-only Perfetto sampled every 10 seconds, with 535 samples per hardware track, 107–108 per effective block, and no nonzero error/data-loss stats returned. Private traces and archive backups remain outside Git.

| Effective block (18 min) | Whole-app CPU seconds | End PSS MiB | Whole-device sampled mean mW | HAL sampled charge drop mAh | Android computed UID mAh (whole 20 min) |
| --- | ---: | ---: | ---: | ---: | ---: |
| ON1 | 43.12 | 50.2 | 513.36 | 44 | 0.8 |
| OFF1 | 20.76 | 50.5 | 551.00 | 42 | 1.2 |
| OFF2 | 17.03 | 54.7 | 483.47 | 34 | 0.8 |
| ON2 | 38.73 | 52.3 | 473.94 | 36 | 0.6 |

Long-measurement PASS replaces the prior short-window limitation. Across the effective windows, whole-app single-core-equivalent CPU was 3.79% ON versus 1.75% OFF; PSS showed no sampled monotonic growth. Energy attribution remains WARNING: ON/OFF sampled whole-device power means were 493.55/517.24 mW, whereas charge deltas were 80/76 mAh, and Android's computed UID attribution was 1.4/2.0 mAh across each 40-minute group. These differing aggregates do not establish collector-only battery cost. Temperature drift (32.8 to 26.1 °C), ordinary background work, shared Wi-Fi ADB/profiler overhead, coarse charge endpoints and 10-second point sampling prevent a defensible daily battery percentage or zero-overhead claim. Power rails were available but not summed because nested rails may overlap. [Perfetto counter scope and units](https://android.googlesource.com/platform/external/perfetto/+/refs/heads/main/docs/data-sources/battery-counters.md), [Android fuel-gauge limitations](https://source.android.com/docs/core/power/device).

The owned fixture and its notification/archive rows were removed with a private reversible backup and transactional preservation of nonfixture history. Main APK/data/schema and energy settings were unchanged. Notification Access and connected collector were restored; owned device trace/config removed. Telegram/Fedora pre- and end-notices succeeded, and the Pixel was explicitly released. Full sanitized numeric evidence: `operations/evidence/notifications-warning-validation-20261010.json`. Original phase 1/2 acceptance remains PASS; no new feature code, APK, migration or emulator work was required.
