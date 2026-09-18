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
