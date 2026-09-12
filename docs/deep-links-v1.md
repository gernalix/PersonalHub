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

Workflowy is treated as an external knowledge surface, not a second PersonalHub database.

### Workflowy -> PersonalHub

Use the canonical PersonalHub URI in a Workflowy node, for example:

```text
personalhub://entity/v1/people/person/48
```

PersonalHub resolves the canonical entity through `HubEntityAdapter` and its current `openTarget`, so Workflowy never needs to know which Android screen implements that entity today.

### PersonalHub -> Workflowy

PersonalHub stores the complete Workflowy URL as an opaque `HubResourceKinds.WEB_URL` resource and links that resource to the canonical entity through Hub Context. PersonalHub validates only that the URL is HTTP(S) on `workflowy.com` (or a subdomain); it does not parse, derive, mirror, or synchronize the Workflowy node structure.

Multiple Workflowy URLs may be attached to one PersonalHub entity. This deliberately avoids a rigid `workflowy_url` column and keeps the same resource mechanism usable for other external systems.

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
