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

## Mobile presentation

The Data Explorer should keep Datasette itself responsible for table, row, filter, facet, pagination, SQL and foreign-key rendering. PersonalHub adds a thin mobile presentation layer rather than recreating those components natively.

Inside PH, both local and remote Datasette pages should receive the same bundled mobile stylesheet/layout treatment: no browser chrome, PH navigation around the WebView, system light/dark compatibility, readable typography and spacing, touch-friendly controls, sticky table headers where practical, horizontal table scrolling instead of squeezed columns, and visually clear foreign-key links. Filters, facets, pagination, row pages and the SQL editor must remain functionally identical to Datasette.

The external-browser action intentionally remains normal Datasette; PH-specific styling is for the embedded WebView only.
