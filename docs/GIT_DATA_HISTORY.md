# PersonalHub Git Data & History

PersonalHub keeps SQLite as the only writable runtime source of truth. Git is an optional versioned history and transport layer. Git data sync is OFF by default and enabling it requires an HTTPS GitHub repository URL plus an access token. PH verifies that the selected repository is private and writable before storing the connection. Credentials remain AES-GCM encrypted with Android Keystore and are not written to the database, exports, logs, saved state or BuildConfig.

Use a PRIVATE data repository, preferably separate from the PersonalHub source repository. Git history is deliberately durable: deleting a current value does not erase older committed versions.

## Architecture

There are three layers:

1. Runtime: personalhub.db plus SQLite WAL. Normal screens and queries use SQLite only.
2. Semantic history: transactional hub_git_events records the meaning of committed domain writes in the same local transaction.
3. Durable history: background Git sync publishes immutable history JSONL, sharded materialized current state, content-addressed BLOBs and control documents.

Git and network operations never run on the interactive write path. Mutations schedule a durable WorkManager push with a 60-second debounce and periodic recovery.

## Provenance

History records provenance on the edit rather than adding a last-author column to every domain table. Each event records event id, timestamp, author, source, optional reason and group id, table and typed primary key, operation, changed columns, and complete typed before/after row state. The shared database gate automatically assigns one group id to all writes in the same outer SQLite transaction; explicit ChatGPT/Codex/revert contexts can override that provenance for their transaction.

Typical authors are user, chatgpt, codex and system. Typical sources are ui, remote_patch, history_revert, migration, import and automation. Remote patches default to chatgpt unless they declare another author. Reverts are new edits and never rewrite old history.

## Repository layout

The logical layout is:

    state/manifest.json
    state/schema.json
    state/tables/<table>/<shard>.jsonl

    history/YYYY/MM/DD/<timestamp>-<digest>.jsonl
    history/YYYY/MM/DD/<timestamp>-<digest>.jsonl.sig.json

    objects/sha256/<prefix>/<sha256>.bin

    changes/<timestamp>-g<generation>.json

    control/manifest.json
    patches/
    migrations/

state is a materialized view of current SQLite state. history is immutable semantic history. One Git commit can contain many history events, so a UI tap does not require its own commit.

BLOBs are stored by SHA-256. JSON references the object hash/path instead of duplicating Base64 data.

## Sharded current state

Materialized tables use deterministic JSONL shards:

- up to 500 rows: 1 shard
- up to 5,000 rows: 8 shards
- up to 50,000 rows: 32 shards
- larger tables: 128 shards

Rows are ordered by primary key and assigned deterministically from a key hash. A normal edit therefore uploads only affected shards. Manifest v2 records shard hashes and row counts; restore remains compatible with the original v1 single-file table representation.

## History signing

Each immutable history batch is signed on-device with EC P-256 / SHA256withECDSA. The private key never leaves Android Keystore. A sibling signature document stores payload hash, public SPKI key and signature. This authenticates PH-produced history payloads independently of Git commit signing.

## Push safety

Automatic push pauses when a batch is unusually destructive: at least 100 deletes representing at least 20 percent of logical rows, or at least 10,000 history events in one batch. No local data is discarded. After review, the UI exposes an explicit force-push action.

## Pull and restore

Git is transport/history, not the database merge engine. PersonalHub never delegates database semantics to a raw Git merge.

Granular import uses hash-verified declarative patches with patch id, schema compatibility, author and optimistic expect preconditions. Pull/sync only discovers and verifies unapplied patches; it never applies them silently. Verified patch ids are surfaced as awaiting review. Patches run transactionally, cannot modify operational tables or primary keys, and finish with FK validation. One patch can be cherry-picked from a branch, tag or commit without merging that Git tree. Before applying it, PH can run the patch against a coherent disposable database copy and report insert/update/delete counts and affected tables; the production database is untouched. Proposal branches under `data/` can be discarded explicitly after review.

History can revert one logical edit. Events sharing group_id are reversed together in reverse order, preserving dependency direction. The revert is itself a new immutable event.

General import reconstructs the selected Git revision into a staging SQLite database. Hash checks, migrations, quick_check and foreign_key_check run before the existing DatabaseVault atomic replacement and rollback path. The application graph restarts only after a verified replacement.

## Schema upgrades

One-shot schema transforms are declarative rather than permanent handwritten Kotlin where possible. Packaged and remote migration documents are hash-verified, resolved as a continuous unambiguous chain and applied only to staging databases. During startup, PH first uses the packaged migration graph; when the APK already understands the target Room schema but no packaged path exists, an enabled/configured Git Data repository may supply the missing declarative chain. PH migrates a detached copy, validates it, then atomically replaces the canonical file; Git OFF never creates a network dependency.

The installed APK remains the compatibility boundary: a remote migration cannot make an old Room model understand an arbitrary future schema. minimum_app_version and target_schema_version therefore fail closed.

## Global History / Time Machine

When Git data sync is enabled, the Home Activity/Registro destination uses global Git History as the user-facing audit/history source.

The platform supports recent edits, filters by author/table/row, record and field blame, statistics, complete PH revision history, table-level and semantic row/field revision diff, granular logical-edit revert with an affected-group preview and sandbox FK/staleness check, complete revision restore by any commit/tag/branch ref, Git-tag milestones, index rebuild, time-window queries, known-good/known-bad change narrowing, proposal sandbox preview/discard, and patch cherry-pick from proposal refs.

hub_git_history_index is a disposable local projection for fast UI queries. Full before/after payloads remain in immutable Git history.

## Timer

Timer still stores its current snapshot because it is runtime state. With Git History enabled it stops appending new local snapshot_history checkpoints/deltas. Timer load-as-of resolves the newest Git state commit at or before the requested timestamp and reads the canonical snapshot row there.

Existing pre-Git snapshot_history rows are preserved automatically. They must not be deleted until their historical coverage has been explicitly migrated and verified. When Git History is disabled, the old local Time Machine remains the fallback.

## Proposal branches and PRs

The history API can create data proposal branches, create pull requests and cherry-pick a declared patch. This supports ChatGPT/Codex-assisted review while keeping SQLite semantics authoritative.

A data proposal is never tested by redirecting the live database to another branch. PH's what-if workflow applies the declared proposal patch to a disposable coherent SQLite copy, validates constraints/integrity, and only then allows an explicit cherry-pick into the live database. This gives branch-style experimentation without creating a second writable production database.

## Storage and performance

Do not commit repeated personalhub.db binaries on every change. A Git commit tree plus `state/manifest.json` is itself the logical checkpoint: unchanged shards/objects keep the same blob identity, so an old complete PH state is addressable without storing another SQLite file. Long-term storage should be dominated by compressed small history events, changed current-state shards and genuinely new content-addressed BLOBs. Content-addressed objects are not garbage-collected merely because the current state stopped referencing them; historical revisions may still require them.

Normal startup never replays Git history. Current UI reads SQLite. History queries use rebuildable local projections, including cached field-lifetime aggregates, rather than rescanning Git. Full reconstruction, old-state reads and repository scans happen only on demand.

## Failure model

A normal PH edit succeeds when the SQLite transaction commits. Git may be offline; pending events remain local and WorkManager retries later. Pull, patch and restore fail closed on incompatible schema, stale preconditions, hash mismatch, FK failure or SQLite integrity failure.

Git substantially reduces ordinary accidental data-loss risk but does not make loss mathematically impossible. Keep the data repository private, protect main from history rewrites where practical, and retain an independent periodic backup.
