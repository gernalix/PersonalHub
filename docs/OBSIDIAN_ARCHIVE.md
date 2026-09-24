# External Obsidian projection

Status: authoritative PersonalHub contract for the optional Obsidian viewer.

## Decision

PersonalHub does **not** implement or own an Obsidian exporter in the Android app.

The canonical flow is:

```text
personalhub.db
    ↓ existing Git Data sync
private PersonalHub-data repository
    ↓ external Fedora projector
shared Obsidian vault
```

`personalhub.db` remains the only writable runtime source of truth. The Obsidian vault is a disposable, read-only projection built outside PersonalHub from the existing Git Data representation.

The external projector is owned by the cross-project Fedora/MegaVault tooling, not by `:app`, `:core:*`, `:contracts:*` or any `:feature:*` module.

## Source contract

The projector consumes the private Git Data repository configured in PersonalHub, not the PersonalHub source repository and not the live Android database.

The useful Git Data surfaces are:

```text
state/manifest.json
state/schema.json
state/tables/<table>/<shard>.jsonl
history/YYYY/MM/DD/*.jsonl
objects/sha256/<prefix>/<sha256>.bin
changes/<timestamp>-g<generation>.json
```

`state/manifest.json` is the authoritative materialized-state index for a Git revision. `changes/` may be used as an incremental hint, but correctness must be recoverable from the manifest plus state shards. History is optional input for historical notes/audit views and must not be confused with current state.

No Android credential, Keystore value, Datasette token, Git token, SAF grant, widget preference or other device-local configuration is expected in this projection.

## PersonalHub responsibilities

PersonalHub is responsible only for its existing Git Data contract:

- publish the current canonical database state in deterministic Git-friendly form;
- publish semantic history and content-addressed BLOBs where applicable;
- keep Git Data independent from Datasette and SAF backup;
- keep secrets and device-local transport configuration out of Git Data.

PersonalHub must not add:

- Obsidian settings;
- vault paths or SAF permissions for Obsidian;
- Markdown renderers;
- Obsidian manifests or queues;
- Obsidian-specific WorkManager jobs;
- feature-level projection providers;
- a runtime dependency on an Obsidian app or vault.

A missing, stale or broken external projector must have zero effect on PersonalHub writes, reads, sync, restore, Datasette or backup.

## Maintained module coverage

The current maintained PersonalHub modules are:

- People
- Timer
- Places
- Substances
- WordPulse
- Soldi

There is no Salute/Health module in the current PersonalHub runtime. External projectors must derive coverage from the current Git Data schema/manifest rather than from obsolete module lists.

## Semantic projection policy

The external projector should turn user-meaningful canonical records into readable notes and avoid mirroring every technical row as Markdown.

Typical useful projections:

- People: contacts and meaningful related user data;
- Places: places and meaningful visits/check-ins;
- Timer: sessions, activities, quick events and user-facing counters/events;
- Soldi: accounts, transactions, recurrences and explicit relations;
- Substances: substances, prescriptions and intake events;
- WordPulse: sessions/summaries or useful aggregates;
- shared Hub data: explicit contexts, tags and relations when they improve navigation.

Technical/cache/runtime tables such as sync queues, generation counters, integrity caches, snapshot internals and transport bookkeeping should normally be ignored even when present in Git Data.

## Stable identity and links

Generated paths must be based on stable canonical identity, not only a human label. Renames must not create unrelated duplicate notes.

Example:

```text
PersonalHub/
  People/contact--<canonical-id>.md
  Places/place--<canonical-id>.md
  Timer/session--<canonical-id>.md
  Soldi/transaction--<canonical-id>.md
  Substances/substance--<canonical-id>.md
  WordPulse/session--<canonical-id>.md
```

Readable wikilinks may use aliases:

```markdown
[[PersonalHub/Places/place--42|Carlo Visda]]
```

Only explicit canonical relations should become semantic links. Label equality alone is not a relation.

The shared projector may also create cross-project wikilinks when a registered identity mapping is explicit and unambiguous.

## Incremental behavior

The Fedora projector should normally react to new commits in the Git Data source and update only affected notes.

Requirements:

1. persist the last successfully consumed Git revision/generation;
2. pull/fetch the source repository safely;
3. inspect the new manifest and, when useful, the `changes/` documents between revisions;
4. regenerate affected semantic documents and their explicitly dependent links;
5. remove only files recorded as projector-owned;
6. commit/publish projector state only after the vault update succeeds;
7. on ambiguity, schema-version change or missing incremental state, fall back to a bounded full rebuild.

A full rebuild must converge to the same generated contents for the same source revision and projector version.

## Vault ownership

Generated notes must carry machine-readable ownership metadata, for example:

```yaml
---
generated_by: sqlite-to-obsidian
source: PersonalHub-data
source_revision: "<git-sha>"
module: places
kind: place
canonical_id: "42"
title: "Carlo Visda"
---
```

The projector may maintain its own manifest/state outside user-authored notes. It must never delete or overwrite unrelated manual files in the vault.

## Relationship to Datasette

Datasette and Obsidian are independent derived views:

- Datasette is for exhaustive structured/tabular exploration, SQL, filtering and aggregation.
- Obsidian is for readable documents, Properties, wikilinks, backlinks and graph navigation.

Neither is a runtime datastore for PersonalHub, and neither replaces the other.

## Testing contract for the external projector

The external service, not PersonalHub Android tests, owns verification for:

- deterministic rendering and escaping;
- stable path under rename;
- explicit-link correctness;
- incremental create/update/delete convergence;
- full rebuild convergence;
- source schema-version changes;
- interruption/retry recovery;
- preservation of unrelated vault files;
- cross-project link resolution where configured.

PersonalHub only needs to keep its existing Git Data contract valid.
