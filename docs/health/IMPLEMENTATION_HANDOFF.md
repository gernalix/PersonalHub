# Salute canonical integration — implementation handoff

Branch: `feature/salute-canonical-domain`

Codex roadmap phases:

- `PROMPT_ID=418763` — canonical Room schema/migration + patch/history foundation
- `PROMPT_ID=724615` — canonical UI + Hub/Temporal + Obsidian/Datasette integration

## Already completed on this branch

- final architectural decision: Salute belongs in `personalhub.db`;
- canonical data model and invariants;
- blood-sample grouping and turnaround semantics;
- measurement/sample/journal AI-comment contracts;
- clinician-note AI meta-assessment/stance contract;
- People/Places/Substances/Soldi integration rules;
- Git semantic-history grouping contract;
- Obsidian-as-primary-reader projection contract;
- minimal Android UI wireframe;
- ChatGPT→Git patch→PH→Git History/Datasette/Obsidian workflow diagram;
- CODE_MAP routing for future Salute work.

## Deliberately deferred to local Codex

These steps must use the local Gradle/Room/AVD toolchain and are intentionally split into two failure domains:

### Phase 418763 — schema/history

1. rebase this branch after the global epoch timestamp task;
2. allocate the next free Room schema version;
3. add health entities/DAO/views and the production migration;
4. run Android consumer preflight before public API/schema changes;
5. export/validate the Room schema;
6. add synthetic ChatGPT patch/history/revert fixtures;
7. validate sample grouping, turnaround and AI snapshot persistence;
8. migrate private legacy health data outside the public source tree when locally available.

### Phase 724615 — integration/UI

1. replace the external `salute.db` repository/cache implementation with canonical DAO queries;
2. wire Hub adapters and temporal provider;
3. extend the actual Obsidian exporter/projection seam found in the post-schema code;
4. ensure Datasette snapshot/replica sees health tables/views;
5. implement the thin read-only Android UI from the SVG;
6. run host gates and Pixel_8a AVD QA.

## Merge policy

Codex must push only this branch.

The user will review and merge it into `main` manually.

The subsequent Git History validation prompt must not run until that merge has happened.
