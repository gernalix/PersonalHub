# Salute → Obsidian projection

Obsidian is the preferred human-reading surface for Salute, but **never the canonical datastore**.

Source of truth:

```text
personalhub.db
```

Projection:

```text
personalhub.db → deterministic exporter → Obsidian Markdown/Bases
```

Deleting the generated Salute folder must be recoverable by running the exporter again.

## Goals

Optimize for:

- long medical notes;
- readable longitudinal history;
- sample-centric review;
- links to People / Places / Substances;
- low-friction manual browsing;
- Obsidian core features and Bases;
- deterministic Git-friendly Markdown.

Do **not** try to reproduce Datasette's arbitrary SQL power inside Obsidian.

Datasette remains the relational explorer; Obsidian remains the reading/knowledge surface.

## Folder layout

```text
PersonalHub/
  Salute/
    Dashboard/
      Salute.md
      Turnaround.md
    Samples/
      2026-09-18--<sample-id>.md
    Journal/
      2026-09-18--<journal-id>.md
    Esami/
      emoglobina.md
      vitamina-b12.md
      fosfatasi-alcalina.md
    _meta/
      export-manifest.json
```

No generated doctor/place/drug duplicates under `Salute/`.

Those entities link to their canonical PH projections when available.

## Determinism

For the same canonical database state, the exporter must produce byte-identical generated content except where an explicitly versioned exporter-format change applies.

Rules:

- stable ordering;
- stable filenames;
- UTF-8;
- LF line endings;
- no current-time “generated at” field inside every note;
- no random UUID generated during export;
- use existing canonical ids;
- sort YAML/frontmatter keys in a documented order;
- sort measurement rows chronologically in analyte pages and consistently in sample pages;
- write files atomically.

`_meta/export-manifest.json` may contain exporter/schema version and hashes used for incremental regeneration.

## Generated-content ownership

Every generated note includes:

```yaml
generated: true
source: personalhub
module: salute
```

Generated notes must not be treated as an ingestion source.

If manual health notes are ever desired, keep them in a separate non-generated location and define a separate explicit import workflow. Do not silently merge manual edits into generated files.

## Common frontmatter

Use properties only for stable/filterable values. Do not stuff long clinical prose into YAML.

Common keys:

```yaml
generated: true
source: personalhub
module: salute
kind: sample | journal | examination
canonical_id: ...
data_ms: 1789682400000
date: 2026-09-18
```

Optional when relevant:

```yaml
sample_id: ...
place_id: ...
clinician_contact_id: ...
category: Ematologia
flagged: true
sample_kind: blood
turnaround_ms: ...
ai_stance: partially_concordant
```

Raw epoch values are acceptable in frontmatter because they support deterministic sorting/querying. Human-facing body text uses formatted dates.

## Sample note

Filename:

```text
Samples/YYYY-MM-DD--<sample-id>.md
```

Example structure:

```markdown
---
generated: true
source: personalhub
module: salute
kind: sample
canonical_id: sample-...
data_ms: 1789682400000
date: 2026-09-18
sample_kind: blood
place_id: ...
turnaround_ms: 111600000
flagged: true
---

# Prelievo · 18 settembre 2026

**Tipo:** Sangue
**Luogo:** [[canonical Places note if available]]
**Refertazione completa:** 1g 7h
**Risultati:** 12 · **fuori range:** 1

## Risultati

| Esame | Risultato | Flag | Refertazione |
|---|---:|---|---|
| [[../Esami/emoglobina|Emoglobina]] | 8.8 mmol/L | | 0g 11h |
| [[../Esami/fosfatasi-alcalina|Fosfatasi alcalina]] | 114 U/L | ↑ | 1g 7h |

## Commento AI del campione

<aggregate sample assessment>

## Evidenze / collegamenti

- medico: ...
- luogo: ...
- note correlate: ...

## Provenienza

<compact source metadata useful for audit>
```

Do not duplicate every low-value technical metadata field in the body. Full source metadata remains queryable in PH/Datasette.

## Examination/analyte note

One file per canonical examination/analyte, not one file per measurement.

Filename derived from the stable canonical name, with collision-safe canonical-id suffix only when necessary:

```text
Esami/emoglobina.md
```

Structure:

```markdown
---
generated: true
source: personalhub
module: salute
kind: examination
canonical_id: exam-...
category: Ematologia
---

# Emoglobina

## Storico

| Data | Valore | Campione | Commento |
|---|---:|---|---|
| 18-9-26 | 8.8 mmol/L | [[../Samples/...]] | ... |
| ... | ... | ... | ... |

## Interpretazione longitudinale più recente

<latest measurement AI snapshot, clearly labelled as AI>
```

This is optimized for the common human question “come è cambiato X nel tempo?”.

## Medical journal note

Filename:

```text
Journal/YYYY-MM-DD--<journal-id>.md
```

Frontmatter may include:

```yaml
kind: journal
canonical_id: ...
data_ms: ...
clinician_contact_id: ...
place_id: ...
ai_stance: concordant
```

Body structure:

```markdown
# <Titolo italiano>

<date / department / clinician / place>

## Nota clinica

<traduzione italiana fedele>

## Meta-parere AI

**Valutazione:** Concorde / Parzialmente concorde / Da mettere in discussione / Evidenza insufficiente

<rationale>

### Incertezze

<material uncertainty, if any>

## Evidenze

- [[sample/analyte/journal links]]
- canonical People/Places/Substances links

## Originale danese

> [!quote]- Mostra originale
> <original Danish text verbatim>
```

The AI section must never look like part of the clinician note.

## Canonical cross-module links

Never manufacture duplicate files such as `Salute/Medici/Dr-X.md`.

Resolution priority:

1. if the global PH Obsidian exporter exposes a canonical vault path for the entity, use that wikilink;
2. otherwise show the canonical label and a PH deep link if supported;
3. otherwise show the label only plus canonical id in metadata.

Examples:

- clinician → People;
- clinic/lab/pharmacy → Places;
- documented medication/prescription → Substances;
- documented purchase → Soldi.

No relationship is inferred merely because names look similar.

## Dashboard/Salute.md

Keep the dashboard small.

Recommended sections:

- recent samples;
- recent journal notes;
- currently flagged measurements;
- recently changed longitudinal AI conclusions;
- links to Bases views.

Prefer Bases for sortable/filterable tables instead of generating giant static Markdown tables.

## Dashboard/Turnaround.md

Purpose: inspect Danish healthcare/lab turnaround.

Show:

- count of blood measurements with valid turnaround;
- mean;
- median;
- min/max;
- recent samples;
- weekend/weekday exploration via Bases or Datasette.

Display all durations as whole days + hours.

Example:

```text
Media:   1g 11h
Mediana: 1g 4h
Min:     0g 3h
Max:     4g 8h
```

Do not treat `chat_received_proxy` as an official hospital publication timestamp; label provenance when interpretation depends on it.

## Bases

The exporter should emit or document Bases configurations for common views when the existing PH vault infrastructure supports generated Base definitions.

Minimum useful views:

- Samples by date;
- Abnormal measurements;
- Journal by clinician;
- Journal by place/department;
- Blood turnaround by date;
- analyte history.

Bases queries rely on stable frontmatter properties, not parsing prose.

## Incremental export

An implementation may avoid rewriting unchanged files using content hashes, but incremental optimization must preserve the same output as a clean full rebuild.

Correctness order:

1. full deterministic build works;
2. golden test confirms same DB → same output;
3. optional manifest/hash optimization.

## Privacy

The Obsidian vault contains health data and must be treated as sensitive.

- no generated vault content in the public PersonalHub source repository;
- tests use synthetic data only;
- no note bodies in logs;
- no health data in CI artifacts;
- destination selection/backup policy follows the user's private vault workflow.
