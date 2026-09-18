# Obsidian archive projection

Status: authoritative design contract for the optional PersonalHub → Obsidian archive viewer.

## Purpose

PersonalHub may export a deterministic Markdown vault for convenient human reading and semantic navigation in Obsidian. This is an **optional read-only projection** of PersonalHub data, not a datastore used by the Android app.

The feature complements Datasette. Neither viewer replaces the other:

- **PersonalHub / SQLite**: operational app and canonical source of truth.
- **Datasette**: structured/tabular exploration, SQL, true foreign keys, filtering, aggregation and dynamic cross-module temporal associations.
- **Obsidian**: long-form reading, Properties, wikilinks, backlinks, graph/local graph and document-oriented navigation.

A user may enable neither, either, or both viewers.

## Non-negotiable invariants

1. `personalhub.db` remains the sole canonical PersonalHub runtime datastore.
2. PersonalHub core/features must never read Markdown to answer normal app queries.
3. Generated Markdown must never be imported back into `personalhub.db`.
4. Obsidian does not replace Datasette and Datasette does not replace Obsidian.
5. The app must behave identically when the vault is disabled, missing, deleted, moved, inaccessible, or Obsidian itself is not installed.
6. Export is one-way:

```text
personalhub.db
    ↓
ArchiveProjectionProvider(s)
    ↓
Obsidian exporter
    ↓
Markdown vault
```

Never:

```text
Markdown → PersonalHub
```

7. No feature may depend on another feature implementation merely to render links. Shared projection contracts belong in a contracts/core boundary; app composition may register feature providers.
8. Generated projection bookkeeping is technical/rebuildable state and must not become semantic Git History, Datasette domain data, or user-visible PH data.

## Relationship to Datasette

Obsidian and Datasette intentionally overlap in the records they can display but optimize for different questions.

Examples:

- A long clinician note is naturally rendered as Markdown in Obsidian.
- A query such as “show every event from every module within ±15 minutes of this transaction” belongs to SQLite/Datasette/Hub temporal logic.
- Obsidian may store an exact timestamp property, but it must not be responsible for discovering cross-module temporal associations.
- If PH already has an explicit canonical relation, the exporter may render it as a wikilink.
- Dynamic timestamp-derived relations remain a Datasette/Hub concern in v1. A later exporter may materialize already-computed relations, but Obsidian must never become their computation engine.

## Projection boundary

Add a narrow projection contract separate from `HubEntityAdapter`. Hub summaries are not a container for entire documents.

Conceptual model:

```kotlin
data class ArchiveDocument(
    val ref: ArchiveDocumentRef,
    val title: String,
    val properties: Map<String, ArchiveProperty>,
    val bodyMarkdown: String,
    val links: List<ArchiveLink>,
    val updatedAtMs: Long?,
)

interface ArchiveProjectionProvider {
    val moduleId: String
    suspend fun documents(request: ArchiveProjectionRequest): ArchiveDocumentPage
}
```

Exact names may vary, but the semantics must remain:

- stable canonical identity;
- bounded/paged reads;
- deterministic output;
- no feature-to-feature implementation dependency;
- no whole-table materialization solely to filter in UI/export code.

The exporter owns Markdown/YAML escaping, paths, manifests, atomic publication and SAF I/O. Feature providers own domain-specific selection and presentation content.

## Stable file identity

File paths must be based on canonical identity, not only the human label. Renaming “Carlo Visda” must not create a second unrelated note or force links to depend on the old label.

Preferred pattern:

```text
PersonalHub/
  People/contact--<canonical-id>.md
  Places/place--<canonical-id>.md
  Timer/session--<canonical-id>.md
  Soldi/transaction--<canonical-id>.md
  Substances/substance--<canonical-id>.md
  WordPulse/session--<canonical-id>.md
  Salute/sample--<canonical-id>.md
  Salute/journal--<canonical-id>.md
```

The visible title belongs in frontmatter/H1. Links should use a readable alias:

```markdown
[[Places/place--42|Carlo Visda]]
```

Do not expose raw database IDs as the primary visible label.

## Generated frontmatter

Every generated document carries machine-readable ownership metadata. Minimum contract:

```yaml
---
ph_generated: true
ph_projection_version: 1
module: places
kind: place
canonical_id: "42"
title: "Carlo Visda"
updated_at_ms: 1789742400000
aliases:
  - "Carlo Visda"
---
```

Domain-specific properties may add exact date-time values, amounts, units, flags and canonical references. Epoch milliseconds may remain in machine properties when useful, but human-facing body text should use the global PH timestamp formatter/contract.

A full date+time may be represented in frontmatter. Daily Notes are not the temporal model for the projection.

## Markdown body

Use Markdown when it materially improves human consumption:

- long clinical notes;
- descriptions and comments;
- AI assessments clearly separated from source text;
- readable summaries;
- explicit related-entity sections.

Do not turn raw technical logs, sync queues, migration metadata, audit internals, binary payloads or ultra-granular implementation rows into notes.

For high-volume domains, project user-meaningful entities/events and aggregate or omit implementation-level rows. Datasette remains the exhaustive structured viewer.

## Wikilinks and backlinks

Only render links supported by canonical identity or an existing explicit PH relation. Never infer a relation solely because labels match.

Examples:

```markdown
Luogo: [[Places/place--42|Carlo Visda]]
Persona: [[People/contact--17|Carlo]]
Sostanza: [[Substances/substance--9|PrEP]]
```

Obsidian supplies backlinks automatically from those links. No reverse-link table or duplicated backlink section is required unless a domain-specific readable summary adds value.

## Long-form health content

Salute is an important consumer of this generic projection, not a special Obsidian subsystem.

Typical generated note:

```markdown
---
ph_generated: true
module: salute
kind: journal
canonical_id: "..."
datetime: 2026-09-16T11:43:21
clinician: "[[People/contact--...|Dr Rossi]]"
---

# Visita del 16 settembre 2026

## Nota clinica
...

## Analisi AI
...

## Evidenze
...

## Originale
...
```

Clinical/source text and AI interpretation must remain visibly distinct.

## Optional settings

The Obsidian exporter must be OFF by default.

Settings should expose only what is necessary:

- enable/disable Obsidian vault export;
- choose/change a SAF tree/directory;
- export/rebuild now;
- compact last-success/error status.

Disabling the feature stops projection work and file I/O. It must not change canonical data.

## Full rebuild

A full rebuild is explicit/recovery behavior, not the normal cost of opening PH.

Requirements:

1. Read canonical data through registered providers in bounded pages.
2. Generate deterministic content.
3. Publish files safely through SAF.
4. Maintain a PH-owned manifest containing generated paths + canonical refs + projection version/hash where useful.
5. Remove obsolete files only when they are known PH-generated files from the prior manifest.
6. Never delete unrelated/manual user files from the selected vault.
7. Same DB state + same projection version => same generated contents/paths.

Generated files are disposable. Users who want personal Markdown should keep it outside PH-owned generated files (for example a separate `Notes/` folder). PH never imports those notes.

## Incremental export

Routine updates should be incremental and coalesced.

A Datasette sync row must never be consumed as an Obsidian acknowledgement. Each consumer requires independent progress state.

Acceptable implementation:

- dedicated Obsidian pending queue/journal; or
- a correctly designed multi-consumer journal with independent acknowledgements.

Requirements:

- INSERT/UPDATE/DELETE can dirty only the affected canonical identities plus explicitly necessary related documents;
- repeated mutations coalesce;
- no whole-vault scan on every DB change;
- WorkManager performs bounded batches and can recover after process death/reboot;
- deleting an entity removes only its PH-owned generated file;
- label changes do not change stable identity paths;
- projection-version changes may trigger a bounded full rebuild;
- turning export OFF cannot break Datasette sync or canonical writes.

A dedicated queue table is rebuildable technical state: keep it out of semantic history, domain sync and user-facing archive projection.

## Atomicity and failure behavior

Canonical SQLite commits must never depend on successful Markdown I/O.

If export fails:

- the database transaction remains committed;
- pending projection work remains retryable;
- existing valid generated files remain usable when practical;
- status records a compact error without sensitive body content;
- next successful run converges the vault to current canonical state.

Never hold the database writer gate while doing slow SAF/network-like document I/O or rendering large bodies.

## Projection coverage

The final v1 should cover user-facing content from all maintained PH modules while avoiding file explosion.

Minimum semantic coverage:

- People: contacts and meaningful user-facing related items.
- Places: places and meaningful visits/check-ins.
- Timer: user-facing sessions/activities, with aggregation allowed where it reduces noise.
- Soldi: accounts/transactions/recurring or exchange records that are meaningful to the user.
- Substances: substances/prescriptions/intake events at a useful granularity.
- WordPulse: sessions/summaries or daily/user-facing aggregates; do not export keystroke/correction-level noise as separate files.
- Salute: samples/examinations/journal and long-form content; measurements may be embedded/aggregated when a separate note would add no reading value.

Exact grain is a renderer decision, but every provider must document its choice and keep it deterministic.

## Testing contract

Use synthetic data only.

Required host tests:

- deterministic Markdown/frontmatter escaping;
- stable path under rename;
- explicit wikilinks only;
- full rebuild idempotence;
- manifest safety: unrelated files survive;
- optional OFF path performs no projection I/O;
- long-form content round-trips exactly enough for reading;
- no PH runtime read path depends on Markdown.

Incremental phase additionally verifies:

- independent acknowledgement from Datasette;
- mutation coalescing;
- create/update/delete convergence;
- interrupted export recovery;
- SAF failure leaves DB safe;
- projection bookkeeping is excluded from semantic history/sync.

Final module phase additionally verifies representative cross-links and bounded file counts for high-volume fixtures.

## Acceptance

The feature is complete when:

- SQLite remains the only canonical PH datastore;
- Obsidian export is optional and removable without affecting PH;
- Datasette remains fully supported in parallel;
- generated Markdown is deterministic, readable and safely owned;
- links/backlinks reflect explicit canonical relations;
- long text is substantially more usable than in tabular views;
- incremental updates do not require rescanning the whole vault;
- all maintained modules have a documented, tested projection policy;
- no Markdown import or second source of truth exists.
