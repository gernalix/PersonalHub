# PersonalHub tags, facets and cross-module links

## Scope

This is the canonical product/architecture specification for tags/facets across **People, Timer, Places, Substances and Soldi**. WordPulse and Salute are explicitly out of scope for the tag UI/engine.

The existing Hub Context system is the foundation for facets, cross-module entity selection and bidirectional links. Do not replace it with a competing graph system.

## Core semantic rule

Keep these concepts distinct:

- **Entity**: something with identity and its own attributes, e.g. a Person, Place, Substance or Account.
- **Event/record**: something that happened at a particular time and is not a reusable label, e.g. a Soldi transaction, Timer session, Substance intake.
- **Tag**: free classification/context that can be attached to entities/records.
- **Facet**: the common UI/search representation of anything that can be selected or used as a filter. A facet can represent an Entity or a Tag without changing its underlying data type.

Never convert People, Places, Substances, transactions or Timer sessions into tags merely to simplify UI.

Examples:

- `👤 Ahsan` -> Person entity exposed as a facet.
- `📍 Copenhagen` -> Place entity exposed as a facet.
- `💊 Meth` -> Substance entity exposed as a facet.
- `🏷 holiday` -> actual tag exposed as a facet.
- a specific 87.50 DKK transaction remains an event/record, never a tag.

## One shared tag engine, not one forced vocabulary

Implement one reusable PersonalHub tag engine and shared UI components. Tags are **namespaced/scoped** by default so module vocabularies do not pollute each other.

Required tag namespaces:

- `people`
- `places`
- `soldi`
- `substances`
- `timer.now`
- `timer.events`
- `timer.since_when`

Also support an optional explicit **global/cross-module** scope for tags intentionally shared between multiple modules. A local picker shows its own namespace plus explicitly global compatible tags; it must not show unrelated module-local tags.

The engine and UI are shared; the vocabulary is not.

## Timer: mandatory separation of tag sets

Today Timer Now, Events and Since When share the same tag definitions. This must end.

After migration:

- Now/Timeline/session/task/time-fence/session-chain behavior uses only `timer.now` tags.
- Events/templates/entries/macros use only `timer.events` tags.
- Since When/life periods use only `timer.since_when` tags.
- Creating, renaming, archiving, deleting or merging a tag in one namespace must not mutate a same-named tag in another namespace.
- If Timer keeps a Tags management screen, it must expose three explicit tabs/sections: **Now | Events | Since when**, each backed by its independent namespace.

Migration of legacy Timer tags must be lossless and deterministic:

1. inspect real references;
2. if a legacy tag is referenced by one surface, migrate it to that surface's namespace;
3. if it is referenced by multiple surfaces, create independent copies in each relevant namespace and remap each surface's references;
4. a legacy tag with no references defaults to `timer.now` so it remains visible/manageable without creating unnecessary copies;
5. preserve applicable metadata/history and do not silently merge same-named tags across namespaces.

## Shared tag data model

Use a normalized common model integrated with the single `personalhub.db`. Exact names may adapt to existing Room conventions, but the model must support:

### Tag definition

- stable id
- namespace/scope
- display name
- normalized name
- optional description
- optional icon/emoji
- optional color
- created/updated timestamps
- archived state
- pinned/favorite state
- optional global/cross-module scope
- module-specific metadata only where genuinely necessary; avoid duplicating the whole engine

Uniqueness is by namespace + normalized name, not globally across every local namespace.

### Tag aliases

Support aliases resolving to one canonical tag, e.g. `CPH` and `København` -> `Copenhagen`.

### Tag assignments

Assignments must support:

- canonical target via Hub entity binding/ref rather than ad-hoc comma-separated text
- tag id
- timestamp
- provenance: `manual`, `suggested` or `automatic`

Suggestions should require user confirmation by default. Do not silently auto-tag existing records unless a narrowly defined rule explicitly says so.

### Optional tag hierarchy

Retain/support parent relationships where useful, but do not force hierarchical browsing on every module. Detect/prevent cycles.

## Migration of existing module tags

Migrate existing data rather than layering another duplicate implementation:

- People: migrate existing `tags` / `contact_tags`.
- Places: migrate existing `place_tags` / `place_tag_cross_ref`.
- Soldi: migrate existing `finance_tags`, transaction/recurrence tag refs and fix the current transaction-editor behavior so selected tags are actually persisted.
- Timer: migrate as described above.
- Substances: introduce tags through the common engine; keep Substance identity/dose/route/timestamps as structured data, not tags.

After migration there must not be multiple live tag engines that can diverge.

## Facets and universal picker

Extend the existing Hub Context adapter/runtime instead of inventing a parallel graph.

Provide one reusable semantic/facet picker that can search compatible adapters and tags from a single field.

Example:

```
Search people, places, substances, tags...
ahs
  👤 Ahsan
cope
  📍 Copenhagen
  🏷 Copenhagen-trip
```

Selected context is displayed as typed chips, for example:

```
[👤 Ahsan] [📍 Netto Nørrebro] [🏷 holiday]
```

The picker must:

- search across compatible entity adapters and tag adapters;
- group/label results by type without requiring permanent separate selectors;
- support autocomplete;
- allow creation of a tag inline when permitted;
- support recent, pinned and frequently used suggestions;
- support contextual/co-occurrence suggestions;
- keep underlying entity identity intact;
- accept `#foo` as a tag-focused shortcut, while normal text searches all compatible facets;
- allow module/type filters when useful;
- avoid showing unrelated namespace-local tags.

Where a structured field is semantically required (e.g. Soldi account, amount, category), keep it structured. The universal picker replaces optional contextual selectors, not required business fields.

## Generic cross-module links and backlinks

Use Hub Context / HubEntityBinding as the common link layer.

A link is stored once and rendered bidirectionally. Do not create duplicate copies of source records.

Example Soldi transaction:

```
Netto · 87.50 DKK · 2026-09-22 18:43
Context:
[👤 Ahsan] [📍 Netto Nørrebro] [🏷 holiday] [🏷 shared]
```

It must appear as a backlink from:

- People -> Ahsan -> Related -> Soldi
- Places -> Netto Nørrebro -> Related -> Soldi
- Tags -> holiday -> Related -> Soldi
- Tags -> shared -> Related -> Soldi

Tapping a backlink opens the original Soldi transaction.

Support many-to-many links (e.g. multiple people). Preserve meaningful relation roles when available (e.g. participant, occurred_at, merchant_place) even when the UI is a single compact picker.

## Shared Related/backlink UI

Provide reusable components for linked items and groups. The same transaction card can render under People, Places or Tags while hiding the facet that is redundant in the current context.

Related sections should expose counts by module/entity kind and remain configurable through the existing Hub-related preferences.

## Global tag browser

Provide a PH-level Tags entry/browser covering the five in-scope modules.

For a tag show:

- usage count
- usage by module/entity kind
- recent linked items
- rename/edit/archive/delete controls when allowed
- aliases
- merge
- pinned state
- description/icon/color if set

Tapping a tag opens all linked records/entities across compatible modules.

## Common tag operations

Implement once and reuse everywhere:

- add/remove multiple tags
- inline tag creation
- autocomplete
- chip rendering
- search/filter by tag
- AND filters
- OR filters
- NOT/exclusion filters
- "No tags" / untagged filter
- recent tags
- most-used tags
- sort by frequency
- sort by last use
- usage counts
- rename
- archive/unarchive
- delete with safe referential behavior
- restore where supported
- merge duplicate tags
- duplicate/near-duplicate warning before creation
- alias management
- pin/favorite
- bulk add/remove tags
- copy tags from another compatible entity/record
- undo through the existing Hub activity/audit machinery
- compact chip overflow (`tag1 tag2 +N`) on small cards

## Search and saved filters

Integrate facets/tags into module search and PH/global search.

Support combinations such as:

- `#holiday`
- person Ahsan + tag holiday
- place Copenhagen + tag restaurant
- AND / OR / NOT combinations

Saved combinations are **saved filters/queries**, not new tags. Keep them distinct from tag definitions.

## Module-specific behavior

### People

- module-local tags remain useful for relationship/context classification;
- facet picker/backlinks allow People entries to participate anywhere without converting people into tags;
- bulk tagging;
- tag filters/search;
- related cross-module items on person detail.

### Places

- tag filters in list and map;
- show/hide map groups by tag;
- multi-tag filtering;
- `favorite`, `to-visit`, `quiet`, etc. remain personal tags, distinct from structured Place identity/category;
- marker styling may derive from a chosen tag/icon/color without hard-coding category duplication;
- bulk tagging;
- related cross-module items on place detail.

### Timer

- three independent tag namespaces as above;
- Now/Timeline analytics aggregate duration by `timer.now` tags;
- compare tag durations and filter timeline by tags;
- preserve timed-tag/time-fence metadata only in `timer.now` where semantically applicable;
- Events use `timer.events` tags for templates/entries/macros and event search;
- Since When uses `timer.since_when` tags for life periods;
- quick-start and tag ranking must use only the relevant namespace.

### Substances

Keep substance, dose, route, timestamp and prescription relationships structured. Tags provide optional context such as `alone`, `party`, `sleep`, etc.

Support tag filtering and descriptive aggregation by tag. Do not infer medical conclusions from the tag system.

### Soldi

Category answers "what kind of expense was this"; tags answer "in what other contexts do I want to find it".

- remove/fix the current non-persisting tag field by wiring it to the common engine;
- retain account/category/person/place as structured concepts where appropriate;
- use the universal context/facet picker for optional Person/Place/Tag/Substance links rather than one always-visible selector per module;
- search must include tags/facets;
- photo-only results remain compatible with facet/tag filtering;
- aggregate spending by tag and period;
- recurrence tag behavior migrates to the common engine without data loss.

## Smart suggestions

Suggestions may use:

- recently used tags
- frequency
- prefix match
- tags commonly used together
- existing linked facets/context

Default behavior is suggestion, not silent assignment.

## Maintenance/statistics

Provide a tag-maintenance view or functions for:

- most used
- recently created/used
- unused
- used once
- archived
- possible duplicates
- aliases
- merge candidates

## Import/export and integrity

The common tag/facet/link tables are part of the single PersonalHub database authority and therefore of canonical backup/export/import.

Never serialize assignments as a comma-separated tag string.

Preserve foreign-key integrity. Migration must be crash-safe under the existing database migration/rollback system.

## UI principles

- One compact semantic picker is preferred over several permanently visible optional selectors.
- Typed icons/chips make entity type visible: person/place/substance/tag.
- Keep required structured fields separate.
- Do not expose internal IDs.
- Cards show at most a small number of chips and then `+N`.
- Long-press/menu may expose rename/remove/edit when appropriate.
- The same shared components should be reused by all five in-scope modules.

## Acceptance direction

The implementation is complete only when:

1. one shared tag engine serves all five in-scope modules;
2. legacy tag data is migrated losslessly;
3. Timer Now / Events / Since When tag vocabularies are independent;
4. the universal facet picker can select entities and tags without converting entities into tags;
5. cross-module links/backlinks work in both directions;
6. Soldi tag selection persists;
7. module-specific filtering/analytics described above work;
8. common maintenance features (rename/archive/merge/aliases/pinned/bulk/untagged/search) are available through shared code;
9. WordPulse and Salute remain unaffected by the new tag UI/engine;
10. Room migration, architecture boundaries, unit tests, emulator/device persistence and deep-link/backlink navigation pass without data loss.
