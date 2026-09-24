# PersonalHub deep-link contract v1

PersonalHub exposes stable permalinks for canonical data. These links identify data, not Android screens, activities, fragments, or current UI layout.

## URI forms

```text
personalhub://entity/v1/{module}/{entity_kind}/{canonical_id}
personalhub://context/v1/{context_id}
personalhub://event/v1/{event_id}
personalhub://search/v1?from={ISO8601}&to={ISO8601}&module={module}
```

Entity links accept one optional navigation hint:

```text
?action=view
?action=edit
```

`view` is the default. `edit` is non-destructive and may fall back to the normal entity screen when a module has no dedicated edit route. No delete, undo, mutation, token, credential, or other destructive operation is part of v1.

Search timestamps are ISO-8601 offset date-times, for example `2026-09-11T11:15:00+02:00`. `module` may be repeated. Missing `from` or `to` uses the Search screen default for that bound.

## Stability rules

- A canonical ID is the persistent record identity. Renaming an entity must not change its URI.
- Android package names, Activity names, tabs, fragments, and visual state never appear in public permalinks.
- Existing v1 URIs must keep their meaning after UI refactors.
- Missing/deleted entities and contexts produce an explicit unavailable state instead of a crash.
- Unsupported versions and actions fail explicitly.
- Opening a permalink is read-only navigation. Mutation always requires an in-app user action.
- Credentials, tokens, secrets, and private authentication material must never be placed in a permalink.

## Workflowy bridge

Workflowy is an optional external knowledge surface, not a second PersonalHub database. The Home setting `Integra Workflowy` is the single global feature gate and is off by default. When off, Workflowy-specific actions, linked-node affordances, settings, Android share target and Workflowy-days background work are hidden/disabled. Existing Hub Context links remain stored and reappear if the integration is enabled again.

### PersonalHub -> Workflowy

Workflowy node links remain ordinary `HubResourceKinds.WEB_URL` resources connected to canonical PersonalHub entities through Hub Context. One PersonalHub entity may therefore have zero, one or many Workflowy nodes without provider-specific database columns or a Room schema change.

With an API key configured in the Workflowy settings, `+ Workflowy note` creates the note directly through the Workflowy API under the configured `parent_id` (default `today`), receives the stable node id, stores the derived deep link immediately, and optionally opens the created node. The API credential is stored only in app-private no-backup storage encrypted with a non-exportable Android Keystore key; it is not stored in the PersonalHub database, exports, Git data or source code.

`Link existing Workflowy node` remains available for an already-created Workflowy URL and uses the same Hub Context resource representation. No temporary three-digit matching token or later reconciliation pass is required.

### Workflowy -> PersonalHub

When the integration is enabled, PersonalHub exposes an Android `ACTION_SEND` text target. Sharing a Workflowy node link to PersonalHub creates a temporary Hub resource anchor and opens the canonical Hub Context composer, where the user selects the PersonalHub entity/entities to link. Cancelling removes the temporary resource; saving keeps the normal Hub Context relationship. The share component is disabled at Android package-manager level while the global integration gate is off.

### PersonalHub permalinks inside Workflowy

The reverse permalink remains supported. Use the canonical PersonalHub URI in a Workflowy node, for example:

```text
personalhub://entity/v1/people/person/48
```

PersonalHub resolves the canonical entity through `HubEntityAdapter` and its current `openTarget`, so Workflowy never needs to know which Android screen implements that entity today.

## Examples

```text
personalhub://entity/v1/people/person/48
personalhub://entity/v1/places/place/17
personalhub://entity/v1/timer/session/9821
personalhub://entity/v1/soldi/transaction/771
personalhub://context/v1/391
personalhub://event/v1/88721
personalhub://search/v1?from=2026-09-11T11%3A15%3A00%2B02%3A00&to=2026-09-11T11%3A49%3A00%2B02%3A00&module=people&module=places
```

The same PersonalHub permalink is intended to be reusable from Workflowy, Obsidian, Telegram, Google Calendar, browsers, QR codes, and other external surfaces without adding provider-specific routing logic.
