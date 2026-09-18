# Salute module — Git contract and Timeline design

Status: design contract for the PersonalHub Salute module. This document defines the implementation target; it does not make PersonalHub a writer of health data.

## Purpose

Salute is a read-only PersonalHub module for health data produced outside PersonalHub, primarily by the ChatGPT → `gernalix/salute` workflow from Min Sundhedsplatform / MyChart material supplied by the user.

The module must not recreate a second manual health-data entry workflow. PersonalHub is a consumer and viewer only.

## Ownership and source of truth

- Canonical health source: private GitHub repository `gernalix/salute`.
- Canonical artifact: `salute.db`.
- Producer: the external ChatGPT/GitHub workflow documented in the salute repository.
- PersonalHub role: read-only replica/cache and UI.
- PersonalHub must never push, edit, merge, restore, rewrite or generate `salute.db`.
- Health rows must not be copied into `personalhub.db`.
- The cached `salute.db` is an external read-only artifact, not a second PersonalHub-owned feature database and not a Room schema.
- No Salute mutation enters PersonalHub autoexport, Git History, Datasette sync or the activity undo log.

This read-only external cache is an explicit exception to the otherwise single-writable-database rule: `personalhub.db` remains the only PersonalHub-owned writable runtime database.

## Reuse of the existing Git channel

Reuse the existing authenticated GitHub transport and Android Keystore credential path, but do **not** reuse `GitDataSync` semantics.

`GitDataSync` is tied to bidirectional PersonalHub logical-state/history synchronization and must never be pointed at the health repository.

Implementation should expose a narrow core read-only artifact API built on the existing GitHub transport primitives:

```text
GitReadOnlyArtifactClient
  checkRemoteHead(repository)
  fetchFile(repository, path, ref)
```

The feature receives bytes/status only. The raw GitHub token must remain inside the existing encrypted core Git settings/transport boundary and must never be exposed to `:feature:salute`.

Repository configuration:
- default repository: `https://github.com/gernalix/salute`
- artifact path: `salute.db`
- branch: repository default branch resolved remotely; never hardcode `main`
- authentication: reuse the existing encrypted GitHub token when it has read access to the health repository
- transport: HTTPS GitHub API only

A missing/insufficient token is a configuration error; do not fall back to a public URL or copy health data into another transport.

## Pull-only synchronization contract

Trigger a remote-head check:
- when Salute opens;
- on explicit Refresh;
- on app startup only when the last check is stale (target: >= 15 minutes);
- optional low-frequency WorkManager refresh may be added later, but no aggressive polling.

Algorithm:

1. Resolve the remote default branch and HEAD commit.
2. If HEAD equals the last successfully installed health revision, return without downloading the DB.
3. Download `salute.db` into an app-private staging file.
4. Reject symlinks/path escapes and enforce a bounded file size. Initial maximum: 25 MiB.
5. Verify SQLite magic/header.
6. Open the staging file read-only.
7. Validate:
   - `PRAGMA quick_check` or `integrity_check` = `ok`;
   - `PRAGMA foreign_key_check` returns no rows;
   - required metadata/contract version is supported;
   - required tables/views and required columns exist.
8. Close the staging connection.
9. Atomically replace the active cache while preserving the last known-good copy until the new file has been reopened successfully.
10. Persist the installed Git revision and sync timestamp outside `salute.db`.
11. Open only the validated active file read-only.

On any failure:
- keep the last known-good database;
- surface a compact non-destructive sync error;
- never replace the active DB with the failed staging file;
- never attempt to “repair” or rewrite the remote DB from PersonalHub.

Recommended private storage:

```text
noBackupFilesDir/salute/salute.db
noBackupFilesDir/salute/salute.db.bak
```

The cache is reproducible from Git and contains sensitive health information, so it should not enter Android cloud backup.

## Producer/consumer database contract

PersonalHub should depend only on stable consumer surfaces, not on arbitrary internal salute tables.

Required metadata:
- `metadata.schema_version`
- add/maintain a distinct `consumer_contract_version`; PH currently requires contract v2 (`2`)

Required views:
- `v_all_health_data`
- `v_medical_journal`
- `v_health_timeline` (stable PH-facing projection)
- `v_test_turnaround`
- `v_health_measurement_history`
- `v_health_journal_detail`
- `v_health_snapshot_evidence`

### `v_all_health_data`

Required columns:

```text
data
data_ms
categoria
esame
risultato
unita
flag
campione
```

### `v_medical_journal`

Required columns:

```text
data
data_ms
tipo
reparto
struttura
clinico
ruolo
titolo
nota
commento_ai
```

### `v_health_timeline`

The producer should expose one compact union view for the Timeline screen. PH should not reconstruct medical semantics from internal normalized tables when a stable projection can do it at the source.

Contract v1 columns:

```text
data            TEXT     -- Italian EEE d-M-yy
data_ms         INTEGER  -- sorting key
tipo            TEXT     -- "Esami" | "Journal"
titolo          TEXT
sottotitolo     TEXT
valore          TEXT
flag            TEXT
dettaglio       TEXT
commento_ai     TEXT
source_kind     TEXT     -- stable machine value: "measurement" | "journal"
source_id       INTEGER  -- stable id within the source table/view domain
```

`source_kind` and `source_id` are navigation keys, not labels. They may be hidden from the normal UI.

For laboratory data, the Timeline UI may group multiple rows sharing the same clinical date/session into one visual card, but the underlying view should remain lossless enough to open every measurement.


## Blood-test turnaround

For blood tests the producer records two separate timestamps:

- sample collection: `test_events.event_epoch_ms`;
- result receipt by ChatGPT: `measurements.received_epoch_ms`.

The second timestamp is a proxy for when the user became aware of the result through Min Sundhedsplatform. It is not silently relabeled as the hospital's official publication timestamp.

A turnaround is valid only when the collection timestamp has `event_time_precision = 'datetime'`. If only the day is known, PersonalHub must show no turnaround rather than compute one from technical midnight.

Consumer view `v_test_turnaround`:

```text
data_prelievo
data_ms
categoria
esame
ricevuto_ms
delta_ms
delta
```

`delta` is already formatted as whole days and hours, e.g. `1g 7h`. The Esami screen may calculate aggregate statistics from `delta_ms`; the first implementation shows the arithmetic mean across rows with a known delta.

Timeline and measurement detail show `tempo_referto` when present.

## Read-only query layer

`:feature:salute` should own a small repository around Android's read-only SQLite API, not Room:

```text
HealthRepository
  timeline(...)
  measurementHistory(...)
  journalDetail(...)
  journalEvidence(...)
  turnaroundSummary(...)
  syncStatus()
  refresh()
```

Rules:
- open with read-only flags;
- no `INSERT`, `UPDATE`, `DELETE`, `CREATE`, migrations or writable WAL;
- no automatic schema mutation;
- fail closed on a newer unsupported consumer contract;
- close/reopen connections around atomic cache replacement.

## Module navigation

Home tile: **Salute**

Default destination: **Timeline**

Top-level destinations:
1. Timeline
2. Esami
3. Diario

No Add/Edit/Delete/FAB actions.

A Refresh action is allowed because it updates the local read-only cache from Git, not health content.

## Timeline screen

### Top bar

```text
<  Salute                            ↻
   aggiornato: oggi 15:42
```

The sync status is secondary. Do not show raw commit SHAs unless the user opens diagnostics.

### Filters

Keep the default screen sparse. One compact segmented/filter row is enough:

```text
[Tutto] [Esami] [Diario] [Anomalie]
```

Optional search filters title, clinician, department, examination and note text.

### Ordering

Primary ordering: `data_ms DESC`.

Never sort by the formatted `data` string.

Group visually by formatted date:

```text
VEN 18-9-26
...
GIO 3-9-26
...
```

### Laboratory card

Prefer a compact card:

```text
Fosfatasi alcalina                         114 U/L ↑
Organi
```

Normal values should remain visually quiet. Abnormal flags should be visible without turning the whole Timeline into an alert dashboard.

When many measurements belong to the same laboratory session/date, collapse them into one summary card when practical:

```text
Esami di laboratorio
12 risultati · 1 fuori range
Albumina 39 · Emoglobina 8.8 · Fosfatasi alcalina 114 ↑
```

Tap → measurement/session detail, including longitudinal history for each analyte.

### Medical-journal card

```text
Consultazione telefonica
Ambulatorio psichiatrico · Spl. <nome>
Prime 2–3 righe della nota tradotta…
```

Tap → journal detail.

### Journal detail

Keep authorship visually unambiguous:

```text
NOTA CLINICA
<traduzione italiana fedele>

ANALISI AI
<commento_ai longitudinale>

EVIDENZE
<misure/note storiche collegate allo snapshot>

ORIGINALE DANESE
<collapsed by default>
```

The AI section must never be styled as if it were text written by the clinician.

### Measurement detail

Show:
- current value + unit + flag;
- chronological history of the same analyte;
- simple trend chart only with >= 2 numeric measurements;
- sample/material where relevant;
- related longitudinal AI snapshots when available.

The chart x-axis uses `data_ms`; formatted dates are presentation only.

## Empty/error states

No local cache:
- “Dati Salute non ancora scaricati”
- primary action: “Sincronizza”

Remote unchanged:
- no toast; update the lightweight status timestamp only if useful.

Remote/validation failure with an old valid cache:
- keep showing the old data;
- compact banner: “Aggiornamento Salute non riuscito · dati precedenti ancora disponibili”

Unsupported newer contract:
- do not partially read the DB;
- show “Aggiorna PersonalHub per leggere questa versione di Salute”.

## Privacy and safety

- Treat health cache as sensitive app-private data.
- Never log note bodies, lab values, Git token or full health queries in production logs.
- Do not expose `salute.db` through Android backup/export flows intended for `personalhub.db`.
- No health-data screenshots or fixtures in the public PersonalHub repository.
- Tests use synthetic fixtures only.
- The UI must distinguish documented clinical content from ChatGPT-generated interpretation.

## Initial implementation acceptance criteria

1. Salute appears as a PersonalHub module and opens Timeline.
2. The module has no mutation controls.
3. It can fetch private `salute.db` through the existing GitHub auth/transport boundary.
4. It never pushes to the health repository.
5. It caches the file under app-private no-backup storage.
6. Invalid/incompatible downloads never replace the last known-good cache.
7. `v_health_timeline` is sorted by `data_ms DESC` and displays blood-test turnaround when available.
8. Journal detail visually separates clinician note, AI snapshot, evidence and Danish original.
9. Measurement detail can show longitudinal history of the same analyte.
10. PersonalHub architecture-boundary tests remain green and `personalhub.db` remains the sole writable PH database.
