# B8 handoff to 193828 — 925612

Implementation target: Room 24, identity `f5ff5327fbc3067a659ded92d66db243`, 99 Room tables. Baseline was main `57e740bba0469477c5d93f8ea93220dd8c50159c`, Room 23 identity `4b9b96396c8f9e750d13b0e6da70fdd9`. Issue #70 remains open pending verified live cutover.

## Prepared external migration

From the integrated repository:

```bash
python3 tools/migrate_personalhub_v23_to_v24.py SOURCE-v23.db --output PREPARED-v24.db
python3 tools/migrate_personalhub_v23_to_v24.py PREPARED-v24.db --output REPLAY-v24.db
python3 tools/migrate_personalhub_v23_to_v24.py PREPARED-v24.db --rollback --output ROLLBACK-v23.db
```

For a classified existing QA-overlay contact, add `--qa-overlay-decision preserve` to the first command. Do not invent a production/external identity or silently delete it. Sources below 23 must first follow the documented historical external upgrade path.

The runner snapshots the source with SQLite backup, including committed WAL state, without modifying the source. It emits the database, `.backup-v23.db`, `.identity-map.json` and `.validation.json` (mode 0600). The backup suffix is retained on schema-24 replay; `source_version` in its report identifies the actual backup schema. Every original column except explicitly migrated identity/reference cells is checked by hash/count; all established IDs are checked exactly. Destination artifacts must be new paths. UUIDs are generated once; replay preserves them. Private maps/databases must stay outside Git.

## Isolated data evidence

A read-only export of PersonalHub-data commit `5429af0111887be738f9c313e71b1c501339ede5` was materialized locally: 90 exported tables, 212 shards, 44,943 rows and 37 binary objects. All downloaded hashes passed. The pinned export is schema 23, app 64, generation 173251; it is an isolated historical input, not a fresh Pixel backup.

Migration registered 4,065 existing addressable rows with complete coverage. Source backup hash: `4193c51f151d64b12e0858885f822551f328610b7a2bb23bf08d12148e1e0d00`. Replay preserved every mapping. Rollback reproduced the coherent source backup exactly and passed schema, integrity and FK checks. The existing QA-overlay contact was explicitly classified and preserved without an external match.

Prepared database: `PersonalHub-data-v24-prepared.db`, with map, validation report, coherent backup and replay/rollback copies; summary `prepared-receipt.json`.

Private artifacts and classification receipt: `/home/daniele/Documents/ChatGPT/Personal Hub/artifacts/925612-isolated/`. These are test evidence, not deployment inputs. Automated test evidence is recorded in `docs/qa/925612.md`.

## Deferred live cutover

193828 must take a fresh coherent backup from the live authority, classify any collisions/QA rows, freeze writes, run the external migration, and compare the emitted map/validation report before replacing anything. Keep the old APK and schema-23 backup together until the new APK passes real runtime and data round trips. Install/replace through the existing canonical procedures; do not uninstall, clear app data, change only user_version, or use destructive Room fallback. Re-enable writes only after the matching schema-24 APK has passed the device gate and actual Git export/readback.

Rollback is a snapshot rollback: freeze writers, preserve a fresh copy of the post-cutover database and sidecars, restore the verified pre-cutover backup with the old APK, then verify runtime and data. It does not reverse edits made after cutover; reconcile those explicitly from the retained copy/history. Never pair an old Room APK with schema 24.

Risks still requiring live acceptance: current device data can differ from the pinned Git export, and previously orphaned sources must fail and be explicitly classified (the old emulator QA fixture exercised this refusal); provider credentials/API access and real Workflowy creation were not exercised; existing schema-23 Git revisions need external migration before restoring into schema 24. History UI, filtering/navigation and Git patch review/preview/apply remain 193828 scope.

B8 performs no Pixel database cutover, no final APK installation, no NV increment and no PersonalHub-data remote write.
