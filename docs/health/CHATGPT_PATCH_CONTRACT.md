# ChatGPT → PersonalHub health patch contract

This document binds Salute ingestion to the **existing** PersonalHub Git patch engine. It does not introduce a new transport or patch format.

Authoritative runtime implementation: `GitPatchEngine` in `core/database/.../capsules/gitdata/GitDataFormat.kt`.

## Existing PH patch envelope

Health ingestion uses the current PH patch format:

```json
{
  "format_version": 1,
  "schema_version": 18,
  "patch_id": "health-import-<stable-id>",
  "author": "chatgpt",
  "reason": "Import MinSP health data",
  "operations": []
}
```

`schema_version` is the exact installed PH Room schema version at patch-generation time; do not hardcode the illustrative value above.

The existing engine applies the full operation list inside one SQLite transaction and sets:

```text
author  = patch.author
source  = remote_patch
reason  = patch.reason
groupId = patch.patch_id
```

Therefore `patch_id` is also the semantic History group for one health import.

## Health patch id

Preferred form:

```text
health-import-<health_import_batches.id>
```

Requirements:

- stable for retries of the same logical import;
- globally unique;
- safe for the existing Git patch id validation;
- one source ingestion/import batch = one patch id.

## Operation format

Use only the existing operations supported by `GitPatchEngine`.

### Insert

```json
{
  "op": "insert",
  "table": "health_samples",
  "key": {
    "id": "sample-example"
  },
  "values": {
    "event_id": "event-example",
    "sample_kind": "blood",
    "material_it": "Sangue"
  }
}
```

### Update

Always use optimistic preconditions for clinically meaningful corrections.

```json
{
  "op": "update",
  "table": "health_measurements",
  "key": {
    "id": "measurement-example"
  },
  "expect": {
    "numeric_value": 8.8
  },
  "values": {
    "numeric_value": 8.9
  }
}
```

### Delete

Deletion/revert semantics remain those of the global PH patch/history engine. Do not introduce Salute-specific delete behavior.

## Insert ordering

A multi-entity health patch must be ordered so every FK is satisfiable at the moment it is inserted.

Typical blood-sample import:

```text
1. health_import_batches
2. optional new canonical People / Places records
3. health_events (sample)
4. health_samples
5. health_examinations not already present
6. health_measurements
7. measurement-level health_ai_snapshots
8. sample-level health_ai_snapshot
9. health_ai_evidence
10. health_source_metadata
11. optional Hub bindings/contexts produced through the supported PH seam
```

Typical medical-journal import:

```text
1. health_import_batches
2. optional canonical People / Places records
3. health_events (journal/visit)
4. health_journal_entries
5. journal-level health_ai_snapshot
6. health_ai_evidence
7. health_source_metadata
8. optional documented Substances prescription changes
9. optional Hub contexts
```

Do not fabricate cross-module rows merely to satisfy an inferred relationship.

## Atomicity

A health import is all-or-nothing.

If any operation fails:

- optimistic precondition;
- primary/unique constraint;
- FK constraint;
- unsupported table/column;
- final `PRAGMA foreign_key_check`;

the existing patch transaction must roll back the entire import.

No partial blood sample, partial journal note, or AI snapshot without its subject is acceptable.

## Deduplication

Deduplication happens **before** patch creation and is reinforced by DB constraints.

Use, in priority order:

- stable source ids/accession ids when available;
- deterministic `source_hash`;
- exact source timestamp/context + source identifiers;
- existing canonical ids.

Never use date-only similarity to silently merge blood samples.

Retries should either:

- produce no patch because the source is already present; or
- produce an idempotent patch whose optimistic expectations demonstrate the intended correction.

## Result receipt / turnaround provenance

For each measurement, preserve the basis of the availability timestamp:

```text
source_timestamp
minsp_notification
chat_received_proxy
unknown
```

When the only available proxy is the timestamp at which the user sent the result to ChatGPT:

- store it as `received_at_ms`;
- set `availability_basis=chat_received_proxy`;
- never describe it as an official publication timestamp.

Turnaround calculations remain derived views, not persisted duplicated durations.

## AI generation sequence

For one import:

1. normalize/document source facts;
2. resolve/deduplicate canonical entities;
3. insert current objective facts conceptually into the candidate state;
4. query historical evidence with clinical timestamp <= snapshot `as_of_ms`;
5. generate measurement AI snapshots;
6. generate the aggregate sample snapshot from the whole sample + history;
7. generate journal meta-assessment where applicable;
8. create evidence links;
9. generate one atomic PH patch.

This sequence ensures the current measurement is included in its own longitudinal context while future data is excluded.

## Medical-note meta-assessment

A journal AI snapshot may contain a normalized stance:

```text
concordant
partially_concordant
questioned
insufficient_evidence
not_applicable
```

The source note itself is never edited to reflect the AI stance.

`comment_it` must explain the reasoning and `uncertainty_it` must record material missing evidence.

## Synthetic example

Illustrative only; no personal health data may be committed to the public PH repository.

```json
{
  "format_version": 1,
  "schema_version": 18,
  "patch_id": "health-import-demo-001",
  "author": "chatgpt",
  "reason": "Synthetic Salute integration fixture",
  "operations": [
    {
      "op": "insert",
      "table": "health_import_batches",
      "key": {"id": "import-demo-001"},
      "values": {
        "received_at_ms": 1790000000000,
        "imported_at_ms": 1790000060000,
        "source_system": "MinSP",
        "source_language": "da",
        "author": "chatgpt"
      }
    },
    {
      "op": "insert",
      "table": "health_events",
      "key": {"id": "event-demo-001"},
      "values": {
        "import_batch_id": "import-demo-001",
        "event_kind": "sample",
        "occurred_at_ms": 1789900000000,
        "utc_offset_min": 120,
        "time_precision": "datetime",
        "title_it": "Prelievo",
        "source_system": "MinSP",
        "created_at_ms": 1790000060000,
        "updated_at_ms": 1790000060000
      }
    },
    {
      "op": "insert",
      "table": "health_samples",
      "key": {"id": "sample-demo-001"},
      "values": {
        "event_id": "event-demo-001",
        "sample_kind": "blood",
        "material_it": "Sangue"
      }
    }
  ]
}
```

The final implementation tests must extend this synthetic fixture to include multiple measurements, per-measurement AI, one sample AI summary, one journal note + meta-assessment, evidence links and cross-domain references.

## Security/privacy

- never place GitHub tokens in patch payloads;
- never include screenshot bytes unless a future explicit canonical attachment design exists;
- never commit real personal health fixtures to the public PersonalHub repository;
- production logs must not print health patch values or note bodies;
- Git History contains personal health data by design when the user's private PH data repo is enabled, so that data repository must remain private.
