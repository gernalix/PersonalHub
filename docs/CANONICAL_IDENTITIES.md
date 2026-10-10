# Canonical identities — B8 / 925612

The shared address is `{entity_kind, canonical_id}`. IDs are opaque, globally unique and permanent; new identities use UUID4. Existing nonempty IDs, including People `public_id`, Places `uuid` and established domain IDs, are preserved byte for byte. Local integer keys and Git shard storage keys remain internal. No matching by name, time, URL or content creates an identity link.

## Authority and coverage

`core/database/src/main/assets/canonical-identities.json` is the exhaustive, machine-readable classification: all 96 schema-23 Room tables, 35 canonical tables, 61 exempt tables and three new registry tables. Exemptions identify subordinate records, relation edges, settings, caches, projections and snapshots. They do not receive UUID columns. The current Timer snapshot also contains externally addressable `timeFenceRules` (`timer/alert`): their identities live in `hub_entities` under `snapshot.timeFenceRules`, while the snapshot/history rows remain exempt.

`hub_entities` is the sole identity authority: canonical ID, qualified kind, owning module, local table/key, lifecycle and timestamps. Its scoped local mapping is unique. Registry rows and identity fields cannot be deleted or reassigned. Physical deletion tombstones the identity, which remains reserved. Unified `hub_tags` owns tag identities; legacy domain tag tables remain compatibility projections.

`hub_external_identities` stores explicit provider tuples `(system, source_scope, external_id)`, the target kind/ID, verification/link method, lifecycle, metadata and timestamps. Tuple uniqueness prevents competing claims, including retired links. External native IDs never replace PH IDs. The operational Workflowy create flow records the returned native node ID as an explicit `workflowy/nodes` link to the separately identified PH resource; manually pasted URLs do not infer native IDs.

`hub_entity_aliases` records permanent directed losing-ID to surviving-ID links. Both identities must exist and have the same kind. Cycles, reassignment and deletion fail. The losing registry row becomes MERGED; resolution follows the chain with cycle detection. Tag merges explicitly write this link. An alias does not silently retarget every historical row or erase provenance.

## Runtime and compatibility

`CanonicalIdentityCapsule` resolves canonical/explicit external identities and translates local keys only at the internal adapter boundary. Shared bindings, Context entity references, Since When sources and affected adapters emit canonical references. Context membership retains its binding FK and separately stores the entity canonical ID.

Added `canonical_id` columns are TEXT NOT NULL with unique indexes. An empty constructor/default value is a legacy compatibility sentinel: insert triggers allocate a UUID4 atomically before the statement completes; updating an existing object with an empty sentinel preserves its ID. Explicit nonempty reassignment fails. Existing canonical columns reject blank or NULL identities. Triggers are generated from the classification by `tools/canonical_identity_schema.py`; Android and the external runner consume the same SQL/validation assets.

Transactions/recurrences store People `person_canonical_id` alongside `personId`. Prescriptions store canonical doctor/transaction references alongside local keys. SQL triggers dual-write either direction; conflicting simultaneous changes and missing targets fail. Soldi People joins resolve through the canonical column. Since When stores canonical source kind/ID and preserves the former local reference in `legacy_source_entity_id`; missing/tombstoned sources remain intelligible through the permanent registry. No source cascade deletes counters. Timer snapshot rule saves update their registry identities in the same database transaction.

## Git, History and restore

The existing unified Git backend exports all three registries, per-canonical-table identity metadata and `personalhub.canonical.v1` in the manifest. Legacy table/shard storage keys remain intact. History events retain old table/row keys and add canonical kind/ID when their target is an addressable entity; new mutation events preserve local compatibility IDs alongside canonical references. Registry maintenance is excluded from duplicate semantic edit events.

Restore imports into detached staging, validates Room shape/hash, canonical coverage/references, trigger whitelist, integrity and foreign keys, and only then replaces the database. A schema-23 Git revision requires external migration before the schema-24 APK can use it. The old revision is preserved; no remote data rewrite or patch-review UI is part of B8.

The external runner refuses unknown schema/hash, collisions, ambiguous local/canonical references, orphan sources and unclassified QA-overlay contacts. A QA-labelled existing ID can be preserved only after an explicit `--qa-overlay-decision preserve` classification. All output artifacts are private and validated before publication.
