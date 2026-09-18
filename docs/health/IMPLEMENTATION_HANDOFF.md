# Salute canonical integration — implementation handoff

Branch: `feature/salute-canonical-domain`

Codex roadmap prompt: `PROMPT_ID=418763`

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

These steps must use the local Gradle/Room/AVD toolchain and should not be guessed through remote file editing:

1. rebase this branch after the global epoch timestamp task;
2. allocate the next free Room schema version;
3. add entities/DAO/views and the production migration;
4. run Android consumer preflight before public API/schema changes;
5. export/validate the Room schema;
6. replace the external `salute.db` repository/cache implementation with canonical DAO queries;
7. wire Hub adapters and temporal provider;
8. extend the actual Obsidian exporter/projection seam found in the post-rebase code;
9. add synthetic ChatGPT patch/history/revert fixtures;
10. run host gates and Pixel_8a AVD QA;
11. migrate private legacy health data outside the public source tree.

## Merge policy

Codex must push only this branch.

The user will review and merge it into `main` manually.

The subsequent Git History validation prompt must not run until that merge has happened.
