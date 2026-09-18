# Salute module — canonical PH integration

Status: target architecture for branch `feature/salute-canonical-domain`.

See also:

- [Canonical data model](health/HEALTH_DATA_MODEL.md)
- [ChatGPT health patch contract](health/CHATGPT_PATCH_CONTRACT.md)
- [ChatGPT → PH workflow](health/chatgpt-to-ph-workflow.svg)
- [Minimal Android UI](health/android-minimal-ui.svg)
- [Obsidian projection](health/OBSIDIAN_PROJECTION.md)

## Decision

Salute is a first-class PersonalHub module, but intentionally **thin and read-only in the Android UI**.

Its purpose is not to create another manual health-tracking app. Its value is that health becomes a canonical PH domain and therefore participates in the existing infrastructure:

- Git semantic history;
- granular/group revert;
- Time Machine / revision restore;
- Datasette;
- Obsidian projection;
- Hub Context;
- temporal search;
- People / Places / Substances / Soldi relations.

The final source of truth is `personalhub.db`.

The previous architecture based on a separate remotely downloaded `salute.db` is superseded by this document and must be removed during implementation.

## User interaction model

### Writes

No Add/Edit/Delete/FAB controls.

Normal health writes originate from:

```text
MinSP / MyChart
      ↓
user supplies screenshot/text to ChatGPT
      ↓
ChatGPT normalizes + translates + analyses
      ↓
PH semantic Git patch
      ↓
GitPatchEngine transaction
      ↓
personalhub.db
```

The Android feature never bypasses this path.

### Reads

Primary human-reading interface: **Obsidian**.

Secondary/ad-hoc analysis: **Datasette**.

Mobile fallback and cross-domain navigation: **PersonalHub Salute**.

## Why Salute belongs inside PH

Health is not isolated from the other modules.

Every health event is temporal and can participate in global temporal search.

Canonical relationships include:

- clinician → People;
- clinic/hospital/lab/pharmacy → Places;
- prescribed/renewed medication → Substances;
- pharmacy purchase → Soldi;
- health event + doctor + place + medication → Hub Context.

No duplicate `health_doctors`, `health_places` or `health_drugs` tables should be introduced.

## Data model

The authoritative specification is [HEALTH_DATA_MODEL.md](health/HEALTH_DATA_MODEL.md).

Core target entities:

```text
health_import_batches
health_events
health_samples
health_examinations
health_measurements
health_journal_entries
health_ai_snapshots
health_ai_evidence
```

### Samples

Every physical collection has one stable `sample_id`.

All analyses generated from the same blood draw point to the same sample.

Sample grouping must use source evidence (accession/specimen id, collection timestamp/context, explicit shared collection), not date-only guesses.

### AI comments

Required scopes:

- one comment for every measurement;
- one summary comment for every sample;
- one meta-comment for every medical journal note;
- optional global health-state snapshots.

All AI comments are visibly and structurally separate from clinician/source data.

For medical notes, the AI meta-comment may explicitly agree, partially agree, question or declare insufficient evidence for the clinician's assessment, with a concrete rationale and explicit uncertainty.

The AI must compare against the complete health record available **up to that clinical time** and must not use future evidence when reconstructing a historical snapshot.

## Blood-test turnaround

For blood tests PH stores separate concepts:

```text
collection time
result availability / receipt time
import time
```

The useful clinical-system latency is:

```text
result receipt - blood collection
```

When MinSP supplies an official publication timestamp, use it.

Otherwise a MinSP notification timestamp may be used.

If neither is supplied, the ChatGPT message receipt timestamp may be stored only as an explicit `chat_received_proxy`.

Never present a proxy as an official hospital timestamp.

Display delta:

```text
1g 7h
0g 19h
3g 0h
```

No decimals.

If only the collection date is known, do not calculate a turnaround from technical midnight.

## Git/history semantics

One ChatGPT ingestion = one `health_import_batches.id` = preferred Git history `group_id`.

A blood-sample import can therefore be reverted as:

- one measurement;
- one AI comment;
- the complete sample;
- the complete ChatGPT import;
- an arbitrary cumulative selection through existing History UI.

Salute must not implement a second history engine.

## Obsidian

Obsidian is a deterministic generated projection of canonical PH data.

Expected structure:

```text
PersonalHub/Salute/
  Samples/
  Journal/
  Esami/
  Dashboard/
```

Generated notes preserve canonical ids and create links/backlinks to People, Places and Substances.

Bases should handle common tables/filtering; Datasette remains the tool for arbitrary SQL.

Obsidian content must be reproducible from `personalhub.db`. Manual Obsidian edits must not silently become canonical PH data.

Logseq DB is not part of this workflow.

## Minimal Android surface

The Pixel UI is intentionally small because it is not the primary reading surface.

Top-level destinations:

```text
Recenti
Esami
Campioni
Diario
```

No module-specific synchronization UI: Salute uses global PH Git/Datasette infrastructure.

### Recenti

Unified timeline ordered strictly by epoch-ms.

Cards show only high-value information:

- title/type;
- value/flag where relevant;
- doctor/place where relevant;
- turnaround when known;
- AI-analysis availability.

### Campione

Show:

- collection date/time;
- place;
- result count and abnormal count;
- sample turnaround;
- whole-sample AI summary;
- compact result list.

Tap a measurement for its longitudinal detail.

### Measurement

Minimal detail:

- current result;
- historical values;
- measurement-level AI comment;
- sample link;
- evidence links.

Charts are optional later; they are not required for the initial thin UI.

### Journal

Visually enforce authorship boundaries:

```text
NOTA CLINICA
<Italian translation>

META-PARERE AI
<stance + rationale + uncertainty>

EVIDENZE
<linked health/cross-module evidence>

ORIGINALE DANESE
<collapsed>
```

Doctor/place/medication chips deep-link to their canonical PH modules.

## Hub Context / temporal integration

Salute should expose adapters for at least:

- event;
- sample;
- measurement;
- journal.

Health events should also expose a Hub temporal provider so global temporal search can discover them without special-casing the UI.

## Migration from the current external implementation

Current main already contains an external read-only Salute implementation that downloads `gernalix/salute/salute.db`.

That implementation is transitional.

The canonical migration must:

1. migrate existing recoverable health records into PH tables;
2. validate exact counts/values/provenance;
3. remove the external DB cache and its Salute-specific transport dependency;
4. keep `gernalix/salute` only as migration/reference history unless explicitly retained for archival purposes;
5. switch `:feature:salute` to `PersonalHubDatabase.healthDao()` / canonical PH query surfaces;
6. keep the existing Home tile but simplify the UI.

## Implementation sequencing

Do not allocate a conflicting Room schema number from this documentation-only branch.

The roadmap already has a global timestamp migration immediately ahead of Salute.

Required order:

1. global PH epoch-ms migration;
2. rebase `feature/salute-canonical-domain`;
3. allocate next free Room schema version;
4. implement Salute entities/DAO/views/migration;
5. implement ChatGPT patch contract and import migration;
6. wire Hub Context / temporal search;
7. wire deterministic Obsidian projection;
8. replace external HealthRepository with canonical PH repository;
9. run Room schema export/migration tests, Git History tests, Datasette tests, architecture boundaries and Android QA.

## Initial acceptance criteria

1. `personalhub.db` is the only canonical runtime DB.
2. Salute has no user-facing CRUD.
3. Existing health data migrates without loss.
4. Same physical blood draw → one stable `sample_id`.
5. Every measurement has a measurement AI comment.
6. Every sample has an aggregate sample AI comment.
7. Every journal entry retains Danish original + Italian translation + AI meta-comment.
8. Blood-test turnaround is derived correctly and displayed in whole days/hours.
9. Doctor/place/medication links resolve to canonical PH entities.
10. Health events participate in global temporal search and Hub Context.
11. Git History can revert one health record or an entire import group.
12. Datasette exposes health tables/views.
13. Obsidian projection is deterministic and regenerable.
14. Android Salute remains read-only and intentionally minimal.
15. No health source text/value is logged in production diagnostics.
