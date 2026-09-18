# Salute — canonical data model

Status: implementation contract for branch `feature/salute-canonical-domain`.

## Architectural decision

Salute is a first-class PersonalHub domain inside the canonical `personalhub.db`.

It is **read-only in the Android UI**, but not read-only at the database layer: trusted remote ChatGPT/Git patches may insert or update health records through the existing PersonalHub transactional patch path.

Consequences:

- no second `salute.db` in the final architecture;
- no second Git history engine;
- no Salute-specific sync transport;
- all health mutations participate in the existing Git semantic history, diff, blame, Time Machine, granular/group revert, Datasette sync and backup pipeline;
- Obsidian is a generated projection/consumer, never the source of truth;
- the legacy external `gernalix/salute` repository is migration/reference material only after canonical migration.

## Design principles

1. Every health record that represents an event has a stable epoch-ms timeline coordinate.
2. Measurements from the same specimen are grouped under one stable `sample_id`.
3. A result-receipt timestamp is distinct from collection time and import time.
4. AI analysis is stored separately from clinician-authored/source data.
5. Every imported medical note keeps the Danish original verbatim and an Italian faithful translation.
6. AI analysis must be longitudinal: it may use only evidence with clinical time <= the snapshot `as_of_ms`.
7. New historical evidence may invalidate an old AI snapshot; re-evaluation increments `assessment_version`.
8. People, Places, Substances and Soldi stay canonical in their own existing PH tables. Salute links to them instead of cloning them.
9. No health UI CRUD is introduced. Writes come from the trusted ChatGPT/Git patch workflow.
10. All persisted temporal fields are INTEGER epoch milliseconds after the global PH epoch migration.

## Entity overview

```text
health_import_batches
        │
        ├──────────────┐
        ▼              ▼
health_events      health_journal_entries
    │                    │
    ▼                    ▼
health_samples       health_ai_snapshots
    │
    ▼
health_measurements
    │
    ▼
health_ai_snapshots

health_ai_snapshots ─── health_ai_evidence

health_events ────────► People / Places
health_journal_entries ─► People / Places
health-derived prescriptions ─► Substances
Substances prescriptions ─────► People / Soldi
```

## 1. `health_import_batches`

One logical user→ChatGPT→PH import. It is the preferred semantic Git `group_id` boundary.

| column | type | rule |
|---|---|---|
| `id` | TEXT PK | stable UUID |
| `received_at_ms` | INTEGER NOT NULL | when the user sent the source material to ChatGPT; also the notification proxy when explicitly used |
| `imported_at_ms` | INTEGER NOT NULL | when the PH patch was generated/applied |
| `source_system` | TEXT NOT NULL | e.g. `MinSP`, `Min Sundhedsplatform / MyChart` |
| `source_language` | TEXT | usually `da` for journal material |
| `source_ref` | TEXT | source page/document/message reference when available |
| `source_hash` | TEXT | deterministic hash for deduplication when source bytes/text are available |
| `author` | TEXT NOT NULL | normally `chatgpt` |
| `notes` | TEXT | provenance only; never invented clinical facts |

Recommended unique constraint: non-null `source_hash`.

## 1b. `health_source_metadata`

Lossless storage for source metadata that is present in MinSP/MyChart but does not justify a permanent first-class column yet.

| column | type | rule |
|---|---|---|
| `owner_kind` | TEXT | `import`, `event`, `sample`, `measurement`, `journal` |
| `owner_id` | TEXT | canonical id of the source object |
| `key` | TEXT | stable normalized metadata key |
| `value` | TEXT NOT NULL | source value; preserve meaning exactly |
| `source_label` | TEXT | original Danish/UI label when useful |

Primary key: `(owner_kind, owner_id, key)`.

Rules:

- capture **all available source metadata** that is not already represented by a stable structured field;
- do not duplicate first-class values into this table merely for convenience;
- do not invent missing metadata;
- promote a repeatedly useful key to a real column only through an explicit schema migration;
- this table is provenance/source data, not a dumping ground for AI interpretation.

## 2. `health_events`

Common event spine for temporal and cross-module integration.

| column | type | rule |
|---|---|---|
| `id` | TEXT PK | stable UUID |
| `import_batch_id` | TEXT FK | → `health_import_batches.id` |
| `event_kind` | TEXT NOT NULL | `sample`, `journal`, `visit`, future kinds allowed by migration |
| `occurred_at_ms` | INTEGER | canonical clinical time |
| `utc_offset_min` | INTEGER | offset at source |
| `time_precision` | TEXT NOT NULL | `datetime`, `date`, `unknown` |
| `title_it` | TEXT | short human label |
| `place_id` | TEXT FK nullable | → existing `places.uuid`, ON DELETE SET NULL |
| `clinician_contact_id` | INTEGER FK nullable | → existing `contacts.id`, ON DELETE SET NULL |
| `source_system` | TEXT NOT NULL | provenance |
| `source_ref` | TEXT | provenance |
| `created_at_ms` | INTEGER NOT NULL | technical creation/import time |
| `updated_at_ms` | INTEGER NOT NULL | technical last update |

This table makes Salute a natural first-class participant in Hub Temporal Search.

## 3. `health_samples`

One physical specimen/collection. All tests produced from one blood draw share one row and therefore one `sample_id`.

| column | type | rule |
|---|---|---|
| `id` | TEXT PK | stable sample UUID |
| `event_id` | TEXT FK UNIQUE | → `health_events.id` |
| `sample_kind` | TEXT NOT NULL | `blood`, `urine`, `swab`, `stool`, `saliva`, `other` |
| `material_it` | TEXT | optional readable material |
| `body_site_it` | TEXT | e.g. gola/retto for swabs |
| `collection_ref` | TEXT | source accession/specimen id when available |

For a blood draw, `health_events.occurred_at_ms` is the collection timestamp.

### Sample identity rule

Measurements belong to the same `sample_id` only when the source supports that they originate from the same physical collection. Matching by date alone is insufficient when multiple collections on the same day are possible.

Priority for grouping:

1. explicit specimen/accession identifier;
2. explicit shared collection timestamp + shared source encounter/context;
3. one screenshot/result group that explicitly states one shared collection;
4. otherwise keep samples separate rather than guessing.

## 4. `health_examinations`

Canonical analyte/test dictionary.

| column | type | rule |
|---|---|---|
| `id` | TEXT PK | stable semantic id |
| `canonical_name` | TEXT UNIQUE NOT NULL | normalized source-independent identity |
| `display_name_it` | TEXT NOT NULL | short Italian label |
| `category_it` | TEXT NOT NULL | e.g. Ematologia, Infiammazione |
| `default_unit` | TEXT | nullable |

Aliases/localization belong in import normalization, not in duplicated measurement rows.

## 5. `health_measurements`

One result/analysis.

| column | type | rule |
|---|---|---|
| `id` | TEXT PK | stable UUID |
| `sample_id` | TEXT FK NOT NULL | → `health_samples.id` |
| `examination_id` | TEXT FK NOT NULL | → `health_examinations.id` |
| `numeric_value` | REAL | use when numeric |
| `text_value` | TEXT | use for non-numeric results |
| `unit` | TEXT | source unit |
| `interpretation_it` | TEXT | source interpretation translated faithfully |
| `flag` | TEXT | normalized `high`, `low`, etc. |
| `result_available_at_ms` | INTEGER | official publication/result timestamp only if the source provides it |
| `received_at_ms` | INTEGER | when the user/ChatGPT received the result |
| `availability_basis` | TEXT NOT NULL | see below |
| `source_ref` | TEXT | result-specific source id if available |

At least one of numeric/text/interpretation must be non-null.

### `availability_basis`

Allowed semantic values:

- `source_timestamp`: MinSP explicitly supplied the publication/result timestamp;
- `minsp_notification`: timestamp is the notification time supplied by the user;
- `chat_received_proxy`: no notification timestamp was available; the ChatGPT message receipt time is used as an explicit proxy;
- `unknown`: no defensible availability time.

Never silently relabel `chat_received_proxy` as an official hospital publication time.

## 6. Blood-test turnaround

For a blood measurement:

```text
collection_ms = health_events.occurred_at_ms
received_ms   = COALESCE(result_available_at_ms, received_at_ms)
delta_ms      = received_ms - collection_ms
```

A turnaround is valid only when:

- `health_samples.sample_kind = 'blood'`;
- collection `time_precision = 'datetime'`;
- a defensible result availability/receipt timestamp exists;
- `delta_ms >= 0`.

Display format uses whole elapsed days and remaining whole hours:

```text
1g 7h
0g 19h
3g 0h
```

No decimals and no technical-midnight calculation when only a date is known.

### Views

Target views:

- `v_health_measurement_turnaround`: one row per blood measurement;
- `v_health_sample_turnaround`: one row per blood sample with first/last/mean result latency;
- `v_health_turnaround_stats`: overall count/mean/median/min/max, plus bounded recent windows where practical.

A sample may receive results at different moments. Sample-level completion latency is the maximum valid measurement latency for that sample.

## 7. `health_journal_entries`

Clinician-authored medical notes.

| column | type | rule |
|---|---|---|
| `id` | TEXT PK | stable UUID |
| `event_id` | TEXT FK UNIQUE | → `health_events.id` |
| `authored_at_ms` | INTEGER | document authoring timestamp |
| `authored_utc_offset_min` | INTEGER | source offset |
| `authored_time_precision` | TEXT | `datetime`, `date`, `unknown` |
| `encounter_type_it` | TEXT | translated type |
| `department_it` | TEXT | translated department |
| `clinician_role_it` | TEXT | translated role |
| `note_type_it` | TEXT | translated note type |
| `title_it` | TEXT | translated title |
| `text_it` | TEXT NOT NULL | faithful Italian translation, not a summary |
| `original_text_da` | TEXT NOT NULL | Danish source verbatim |
| `source_ref` | TEXT | source id/reference |

The event row owns encounter date, clinician and place. The journal row owns authoring-specific metadata.

## 8. `health_ai_snapshots`

Current AI assessment attached to one health subject.

| column | type | rule |
|---|---|---|
| `id` | TEXT PK | stable UUID |
| `subject_kind` | TEXT NOT NULL | `measurement`, `sample`, `journal`, `health_state` |
| `subject_id` | TEXT NOT NULL | canonical subject id |
| `as_of_ms` | INTEGER NOT NULL | clinical cutoff; no future evidence |
| `generated_at_ms` | INTEGER NOT NULL | generation time |
| `generated_by` | TEXT NOT NULL | normally `ChatGPT` |
| `model` | TEXT | model if known |
| `assessment_version` | INTEGER NOT NULL | increments on re-evaluation |
| `stance` | TEXT | for clinician-note meta-assessment; see below |
| `comment_it` | TEXT NOT NULL | concise longitudinal analysis |
| `uncertainty_it` | TEXT | missing data/ambiguity |

Unique current-state key: `(subject_kind, subject_id)`.

Git History is the history of prior snapshot versions; the live database keeps the current assessment.

### Required AI coverage

Every imported health entry must have the applicable AI layer:

- every measurement → `measurement` snapshot;
- every sample → one `sample` snapshot summarizing the whole specimen/panel;
- every journal note → `journal` snapshot;
- optional milestone/global import → `health_state` snapshot.

### Measurement comment

Must discuss the value in longitudinal context, not only against a static reference range.

Example evidence dimensions:

- previous values of the same analyte;
- direction and magnitude of change;
- related measurements from the same sample;
- documented treatment/events before `as_of_ms`;
- relevant prior notes.

### Sample comment

Must summarize the **entire sample**, including:

- overall pattern;
- notable abnormal results;
- reassuring/stable results when clinically relevant;
- relationships between analytes;
- comparison with previous samples;
- uncertainties.

It must not simply concatenate individual measurement comments.

### Journal-note meta-comment

The AI comment must evaluate the clinician note in the context of the complete prior health record available up to `as_of_ms`.

It may explicitly state agreement/disagreement, but as an **AI meta-assessment**, never as clinician-authored fact.

Normalized `stance` values:

- `concordant`: available evidence materially supports the clinician's assessment;
- `partially_concordant`: mixed/partially supported;
- `questioned`: specific evidence gives a reason to question part of the assessment;
- `insufficient_evidence`: available data cannot fairly adjudicate it;
- `not_applicable`: note contains no substantive clinical assessment to agree/disagree with.

The rationale belongs in `comment_it`. Any uncertainty belongs in `uncertainty_it`.

Do not convert disagreement into a definitive diagnosis. Prefer concrete statements such as:

> “Il dato X non sostiene la conclusione Y; tuttavia manca Z, quindi l'alternativa resta incerta.”

## 9. `health_ai_evidence`

Auditable evidence used by an AI snapshot.

| column | type | rule |
|---|---|---|
| `snapshot_id` | TEXT FK | → `health_ai_snapshots.id` |
| `evidence_kind` | TEXT | `measurement`, `sample`, `journal`, `people`, `place`, `substance`, `prescription`, `transaction` |
| `evidence_id` | TEXT | canonical id in target domain |
| `relevance_it` | TEXT NOT NULL | why it mattered |
| `position` | INTEGER NOT NULL | stable display order |

Primary key: `(snapshot_id, evidence_kind, evidence_id)`.

Evidence links are audit metadata. The primary semantic cross-module graph remains Hub Context.

## 10. Cross-module integration

### People

A doctor/clinician is a canonical People record.

Health stores only `clinician_contact_id` references. If an import names a new clinician with enough identifying evidence, the ChatGPT patch may create the People record first and then reference it.

### Places

Hospitals, clinics, labs and pharmacies are canonical Places.

Health event → `place_id`.

Never create a duplicate place merely because MinSP uses a slightly different label; resolve aliases where possible.

### Substances

If a note documents a new prescription/renewal with sufficient structured details, the same ChatGPT patch should update the existing Substances domain rather than creating `health_drugs`.

The health note and prescription are related through Hub Context/evidence metadata.

No prescription is inferred from a mere medication mention.

### Soldi

Medication purchase belongs to Soldi and normally enters through transaction/receipt evidence.

The existing Substances prescription → `finance_transaction_id` bridge should be used where appropriate.

A health prescription must not fabricate a purchase.

### Hub Context

Salute must expose adapters for at least:

- health event;
- sample;
- measurement;
- journal entry.

Typical contexts:

```text
“Visita psichiatrica 18-9-26”
  doctor      → People
  place       → Places
  note        → Salute/journal
  medication  → Substances
  prescription→ Substances prescription (when adapter exists)
```

## 11. Consumer views

The live database should expose stable views optimized for Datasette, Obsidian and the thin Android UI:

- `v_health_timeline`
- `v_health_samples`
- `v_health_sample_measurements`
- `v_health_measurements`
- `v_health_journal`
- `v_health_measurement_turnaround`
- `v_health_sample_turnaround`
- `v_health_turnaround_stats`
- `v_health_ai_evidence`

### `v_health_timeline`

One unified temporal projection with at least:

```text
data_ms
kind
entity_id
title
subtitle
value
unit
flag
sample_id
place_id
clinician_contact_id
ai_stance
ai_comment
```

Formatting such as Italian `EEE d-M-yy` is presentation; sorting always uses `data_ms`.

## 12. Obsidian projection

Obsidian is the preferred human-reading interface but remains fully disposable.

Target generated structure:

```text
PersonalHub/
  Salute/
    Timeline/
    Samples/
      2026-09-18--<sample_id>.md
    Journal/
      2026-09-18--<journal_id>.md
    Esami/
      emoglobina.md
      vitamina-b12.md
      ...
    Dashboard/
      Salute.md
      Turnaround.md
```

Generated notes carry stable properties such as:

```yaml
module: salute
kind: sample
canonical_id: ...
data_ms: ...
place_id: ...
clinician_contact_id: ...
generated: true
```

Rules:

- deterministic output from `personalhub.db`;
- generated files are not canonical input;
- preserve PH canonical ids for links/backlinks;
- link doctors to People, places to Places and medications to Substances using the canonical Obsidian projection conventions;
- use Bases for day-to-day tables/filters;
- use Datasette for arbitrary/advanced SQL exploration;
- no Logseq DB ingestion step is part of the architecture.

## 13. Git semantic history

Every ChatGPT import is one logical `group_id` equal to the import batch id.

Example import:

```text
group health-import-...
  + health_import_batches
  + health_events(sample)
  + health_samples
  + N health_measurements
  + N measurement AI snapshots
  + 1 sample AI snapshot
  + optional health_events(journal)
  + optional health_journal_entries
  + optional journal AI snapshot
  + optional People/Places/Substances changes
```

Because all writes occur through the existing PH patch transaction and tracking layer, the existing infrastructure should provide:

- single-record revert;
- grouped import/sample revert;
- cumulative revert;
- diff/blame;
- Time Machine;
- revision restore;
- Datasette replication.

No Salute-specific history implementation should be added.

## 14. Migration sequencing

The current main branch is Room schema v15 while the roadmap already reserves the next schema transition for the global timestamp migration.

Therefore this branch intentionally defines the **target model** but does not allocate a conflicting Room version remotely.

Implementation sequence:

1. complete the existing global epoch timestamp migration;
2. rebase this branch onto that post-migration main;
3. allocate the next free Room schema version;
4. add Health Room entities/DAO/views + production migration;
5. migrate the legacy external `salute.db` records into canonical health tables exactly once;
6. remove the external Salute cache/transport implementation;
7. run schema export, migration tests, Git History tests, Hub Context tests and Android QA.

The roadmap task is authoritative for steps requiring local Gradle/Room tooling.
