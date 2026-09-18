# PersonalHub Data Explorer

PersonalHub uses a hybrid Datasette model without changing data ownership.

## Source of truth

`personalhub.db` on Android remains the only writable source of truth. Datasette must never bypass Room/domain validation, the Activity register, undo, import/export gates, or sync journaling.

## Local mode

`DataExplorerSnapshots` creates a checkpointed and validated snapshot under a dedicated cache directory. Only that detached file is exposed to the WebView; the live Room database and WAL are never served.

The embedded Datasette Lite runtime belongs under `app/src/main/assets/datasette-lite/`. `DataExplorerActivity` serves the static runtime and the detached snapshot through `WebViewAssetLoader` on the synthetic HTTPS origin `appassets.androidplatform.net`.

Local mode rejects network requests outside that synthetic origin. A build is therefore offline-capable only when the vendored Datasette Lite, Pyodide and required wheels/assets start successfully with networking disabled.

## Remote mode

Remote exploration reuses the HTTPS base address already stored by Datasette sync but never reads or injects the mobile Bearer token. The default presentation database is `personalhub_read`, matching the canonical `datasette5` projection, and can be overridden in the explorer.

The technical `personalhub.personalhub_entities` envelope remains separate from the human presentation. Remote browsing and read-only SQL use the server's interactive authentication and presentation permissions.

## Writes

Arbitrary Datasette SQL writes are intentionally unsupported. Any future mutation exposed from a Datasette view must call a PersonalHub-owned mutation API so validation, sync journaling, Activity logging and undo remain intact.


## Module entry points

Feature modules open the explorer through `DataExplorerContract` in `:contracts:database`; they do not depend on `DataExplorerActivity` or WebView code. The default contextual tables are:

- People → `contacts`
- Timer → `sessions`
- Places → `places`
- Substances → `substances`
- WordPulse → `word_entries`
- Soldi → `finance_transactions`

Datasette Lite receives the table as a fragment route after the local snapshot is loaded. Remote mode appends the same validated table name below `personalhub_read`.


## Native foreign-key navigation

Datasette's own relationship UI is part of the product contract, not something PersonalHub should reimplement in Compose. When a SQLite column has a real foreign key, Datasette must render the referenced record as a labelled hyperlink and the referenced row must expose backlinks to rows that reference it. Semantic labels must come from explicit `label_column` metadata where automatic inference would be ambiguous.

Remote mode already uses the relational `personalhub_read` projection for this purpose. Local mode must provide equivalent navigation semantics. It may read the detached raw snapshot as input, but it must not stop at exposing raw IDs when a canonical relationship is known. If a relationship is not represented by a physical FK in the raw Room snapshot, the offline runtime must build an ephemeral read-only presentation database from that snapshot with real SQLite PK/FK constraints and semantic label columns. The live `personalhub.db` is never altered.

Local and remote acceptance therefore includes forward FK links, reverse/backlink navigation and readable labels for the same canonical relationships. Technical IDs remain available in row detail/API/SQL but should not replace human labels in normal table browsing.


## Cross-module relation graph

Foreign-key navigation must work as a peer-to-peer graph, not as a hierarchy with one privileged module or entity type.

Known direct relationships stored as ordinary columns are projected as real SQLite foreign keys even when Room cannot safely declare the cross-feature constraint directly.

Relationships created through PersonalHub Context use the Context graph as their source of truth. The read-only presentation materializes every resolvable unordered pair of Context members into the symmetric derived table `hub_entity_relations`. Each pair is stored exactly once with native foreign keys to both concrete presentation rows; Datasette can reach the same edge from either endpoint through its normal FK/backlink navigation.

Therefore any supported record can be the starting point: a Place can lead to a Timer session, Soldi transaction or Substance; a transaction can lead back to the Place or onward to another Context member; a Substance can lead to related sessions, resources, people or other entities. People are one peer among the others, not a hub.

The graph currently resolves every Hub entity kind registered by PersonalHub that has a concrete presentation table, including People, Timer sessions, Places, Soldi transactions, Substances, intakes, WordPulse sessions and Hub resources. Adding another Hub entity kind later must extend this mapping without changing the peer-to-peer model.

The bridge is derived presentation data only. It must never create new canonical associations, infer relationships from names, or write back to `personalhub.db`. Unresolved/deleted bindings remain explicit and must not silently point to a different record.

The normal Datasette row page must retain native forward foreign-key links and reverse related-row navigation. The PH mobile row template may aggregate those same FK-backed relationships into a **Related across PersonalHub** section grouped by module/entity kind for readability, but every displayed relationship must still resolve to a real Datasette row and remain queryable through Datasette SQL/API/filtering.


### Duplicate suppression

Cross-module relations are deduplicated by unordered entity pair. The same two records appear once in **Related across PersonalHub** even if multiple Contexts support that relationship. Original Context IDs remain available as provenance.

An exact Context signature is based on context type, normalized title and the unordered set of member bindings plus roles. Repeated Contexts with the same signature are retained as source provenance but marked duplicate; they must not produce repeated Datasette links. Distinct Contexts that happen to connect the same pair still produce one visible relation with multiple provenance records.


## Temporal associations

Timestamp-based relationships are a separate evidence layer, not a replacement for native foreign keys or explicit Contexts.

The local and remote presentation must expose the same temporal policy:

- completed Places visits are intervals derived from matching CHECK_IN/CHECK_OUT events;
- completed Timer sessions are intervals;
- Soldi transactions and substance intakes are timestamped points;
- People participates only through timestamped contact events/initiatives, never by inferring that the contact record itself was physically present;
- WordPulse uses derived activity bursts, not raw long-lived session intervals: consecutive entries from the same session with gaps <=5 minutes are grouped, and singleton bursts are ignored;
- intervals longer than 24 hours are excluded;
- interval/interval requires >=50% overlap of the shorter interval; >=80% is high only when durations differ <=4x;
- point/interval requires the point inside an interval <=12 hours; <=3-hour containers are high, longer containers are medium;
- point/point requires <=5 minutes distance; <=60 seconds is high.

Only different modules from the same source installation are compared. `confidence` means temporal match strength, not semantic certainty.

The visible inferred graph is `hub_temporal_relations`. Every endpoint column is a real FK to its presentation record, so Datasette can render native links/backlinks. Explicit relations win: if the same endpoint pair is already connected by a native FK or `hub_entity_relations`, the temporal candidate is suppressed from visible backlinks and retained only as evidence. For Context links, the visible explicit relation receives aggregate temporal support fields instead of a duplicate row.

The embedded mobile row page should therefore show two distinct sections:

- **Related across PersonalHub** — authoritative FK/Context relationships;
- **Temporal associations** — inferred timing relationships, high first and medium collapsed by default.

A user can expand the evidence fields (overlap, time distance and source interval/point) when needed. No temporal inference may create, mutate or persist a canonical relationship in `personalhub.db`.

## Mobile presentation

The initial shell currently embeds Datasette's normal HTML UI in a WebView, so before the mobile presentation layer is installed it is intentionally close to opening the same Datasette page in a mobile browser, minus browser chrome. The production target is more optimized than that baseline while preserving Datasette semantics.

The Data Explorer should keep Datasette itself responsible for table, row, filter, facet, pagination, SQL and foreign-key rendering. PersonalHub adds a thin mobile presentation layer rather than recreating those components natively. On narrow screens the preferred default is a readable card/list representation with an explicit switch to the dense table when useful; foreign-key values are rendered as prominent tappable labels/chips, and row detail groups incoming/outgoing relationships by PersonalHub module.

Inside PH, both local and remote Datasette pages should receive the same bundled mobile stylesheet/layout treatment: no browser chrome, PH navigation around the WebView, system light/dark compatibility, readable typography and spacing, touch-friendly controls, sticky table headers where practical, horizontal table scrolling instead of squeezed columns, and visually clear foreign-key links. Filters, facets, pagination, row pages and the SQL editor must remain functionally identical to Datasette.

The external-browser action intentionally remains normal Datasette; PH-specific styling is for the embedded WebView only.
