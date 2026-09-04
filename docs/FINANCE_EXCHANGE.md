# Soldi accounts and Git interchange

PROMPT_ID: 684213. Room 5 / application 14 extends the existing finance capsule. No original Soldi data is imported and no receipt content is stored or processed.

## Accounting model

Each transaction has one required account FK. An account has a stable UUID, name, ISO currency, opening balance, opening instant and a persisted inclusion flag. Balance at an instant is the opening balance plus all signed transactions on that account up to that instant. Before the opening instant it is zero; new transactions cannot precede the opening. No independently editable current balance exists. Totals include only selected accounts and remain grouped by currency; PH does not invent exchange rates.

Reconciliation computes the difference inside the same writer transaction as the compensating entry. Zero produces no entry. The generated transaction can be edited/deleted normally; it uses the chosen title/notes and a reconciliation tag. The list stays central, month navigation filters transactions, and the selected-account total stays outside the scrolling list at the bottom. Account cards are grouped by currency, and each currency has its own bottom total and included-account names. Timestamps use localized readable dates/times, edited with native date and time pickers; storage remains UTC. Contextual screen titles replace repeated module branding. Description, merchant and tag fields suggest matching existing values (tags complete the last comma-separated token); currencies offer matching codes. Monetary fields use a numeric keyboard. Suggestions are local and optional. Accounts expose their inclusion checkbox, opening/current balance, editor and reconciliation action, following the supplied reference layouts within existing Material conventions.

A selected product carries its actual local FK through the draft, picker, save and reopen. Renaming a product changes its display label without changing identity. Product and transaction UUIDs are stable interchange identities; integer local row IDs remain stable too. Migration 4→5 preserves finance transaction values/IDs and tag links, assigns deterministic per-currency default accounts, and adds stable UUIDs. It uses Room's generated schema and refreshes the existing sync journal so the new fields/accounts reach replicas. Imports of earlier PH backups still upgrade only in staging.

## Optional Git transport

Settings accepts a credential-free HTTPS repository URL. It uses the dedicated `soldi` branch and `soldi.json` file. The first Push can create that branch on an empty, anonymously writable test server. Pull works with public repositories; authenticated/private Git and ordinary authenticated GitHub Push are explicitly unsupported. The existing Datasette token is never reused. JGit is a pure Java transport; it runs on the IO dispatcher with timeouts and serialized explicit actions, never from a timer. URL userinfo, query strings and fragments are rejected. No credentials are stored in URLs, SQL, interchange files or logs. HTTP loopback is enabled only for the isolated `.qa` package and its local test fixture.

The branch tree must contain only the regular file `soldi.json`. The app rejects extra paths, directories, symbolic links and oversized content before checkout; private temporary Git objects are removed after the operation. Keep receipt images/text and unrelated PH data out of this dedicated branch and its history. No SQLite file is transported. The branch file contains version 1, accounts, products and transactions, sorted by stable ID with recursively sorted object keys. Amounts are canonical decimal strings (no exponent, no unnecessary trailing zeros), dates canonical UTC instants, UUIDs lowercase, and booleans actual JSON booleans. Use an exported file as the template. Unknown/missing fields, duplicate stable IDs, unknown FKs, noncanonical data and invalid amounts reject the entire pull atomically.

Account records have `id`, `name`, `currency`, `openingBalance`, `openedAt`. Inclusion is local UI preference and is never overwritten by Git. Product records have `id` (UUID) and `name`. Transaction records have `id`, `accountId`, nullable `productId` (product UUID), nullable `title` (general transaction description), `amount`, `currency`, nullable `chain`, nullable `placeId` (an existing PH Places UUID), `notes`, `tags` (canonical comma-separated sorted tags), `fromReceipt`, `occurredAt`, `createdAt`, `updatedAt`. Product transactions normally use null `title`. There are no receipt identifiers or receipt payloads. External ChatGPT processing adds or edits these finance records only.

## Conflict and deletion rules

A successful exchange remembers the remote commit and each remote record's canonical SHA-256 in local connection preferences. Pulling identical records is a no-op. A changed existing record is accepted only if its current local hash still matches the last acknowledged remote hash; transaction timestamps must not move backwards. Changed local data, local deletion of a previously known UUID, or conflicting unknown IDs abort the whole pull. Missing remote rows never delete local rows. This is a conservative interchange workflow, not automatic bidirectional deletion replication; deleted records are removed from the next explicitly pushed snapshot. Resolve divergent edits deliberately in the exchange source/local records rather than force-writing over them.

Push is allowed only if the remote branch still equals the acknowledged commit (or both are absent). A new remote commit requires Pull first. Remote history rewrites are rejected. Push is never forced; server-side rejection after a race leaves the prior baseline intact. Snapshot creation and import use the normal Room gate; HTTP stays outside it. A local edit during upload remains different from the acknowledged snapshot and cannot be erased by a later pull. Git temporary worktrees are private and removed after each operation. Local connection metadata does not enter the shared finance payload.

## Verification and safe deployment

JVM tests cover signed/account/subset balances, preserved product identity after rename/edit, reconciliation and zero difference, old-row/tag migration, deterministic interchange, two-database round trip, idempotence, atomic invalid/conflicting input, and rejected credential-bearing URLs. The isolated `FinanceAccountsInstrumentedTest` exercises account/product/reconciliation persistence, explicit actual Git Push/Pull, duplicate prevention, sync-journal participation and validated automatic SAF readback. It refuses the main app package. Final main-package updates on Pixel and TCL happen only after QA passes, using coherent backups and in-place installation; no main-package uninstall, reset or destructive instrumentation is permitted.

JGit Java baseline reference: https://help.eclipse.org/latest/topic/org.eclipse.egit.doc/help/EGit/Contributor_Guide/Manual-Developer-Setup.html


### Verified result — 2026-09-04

- Targeted JVM gates passed: 17 core database/export/sync/finance tests and 4 application routing tests. The account test also proves separate DKK/EUR totals.
- Isolated instrumentation passed on TCL and then, at the user's request, on Pixel 8a with the final code (Pixel: 7.333 s). It exercised real JGit Push and repeated Pull, canonical product persistence, reconciliation, sync journal and validated automatic SAF export.
- Pixel UI verified readable timestamps, contextual titles, existing-description and currency suggestions, account creation, selection changes (120→175 DKK alongside an independent 20 EUR), reopened canonical product, and successful explicit Push/Pull. An external synthetic -2 DKK transaction with `fromReceipt=true` was committed to the local fixture, pulled twice, and appeared once: 173 DKK and 20 EUR. Final QA database had 4 transactions, 3 selected accounts and valid product/FK/integrity checks. The earlier TCL UI verified native date/time pickers and a +10 reconciliation. A slow first TCL QA load was investigated with an intact database snapshot; subsequent reopening loaded all saved rows. No real data was involved.
- After QA PASS, normal `14.apk` was built and installed **in place on both main packages**: Pixel 12→14 and TCL 8→14. Both reported versionCode/versionName 14 and successful cold launch with the visible home version. Matching installed signatures were accepted by both package managers.
- Pixel received a coherent private app backup. TCL v8 was not debuggable, so its native verified SAF export supplied the backup; the temporary folder setting was subsequently removed to restore its previous unconfigured state. No main uninstall, reset or destructive instrumentation occurred.
- Both migrated databases passed integrity/foreign-key checks at Room schema 5; all rows of all 54 preexisting domain tables matched their respective backups exactly. Both main finance transaction tables remain empty: no standalone Soldi import and no QA-data import.
- QA/test packages, synthetic device export folders and ADB fixture forwarding were removed; the local Git fixture server was stopped. Private evidence and backups remain outside Git under the task's `artifacts/684213` directory.


### Compact transaction cards — application 15

The transaction list now puts the title and amount on one line, with account/date beneath. Empty merchant/place rows are omitted; nonempty supplementary details use one truncated preview line and remain fully accessible in the editor. Tapping a card edits it. An accessible 48 dp overflow target contains Edit/Delete, retaining the deletion confirmation without a permanent action row. Typical two-line cards are 64 dp tall. No persistence or accounting behavior changed.

Debug and isolated QA builds passed. Pixel QA with synthetic records verified readable two/three-line cards, direct card editing, overflow editing and deletion confirmation. Version 15 is an in-place UI update; no original Soldi data or QA fixtures enter the main app.

Pixel and TCL main packages were updated in place from 14 to 15 and both launch/version checks passed. Canonical signing was accepted by both installers. The isolated Pixel QA package was removed. This follow-up is UI-only; no roadmap entry was advanced.


### Title suggestions reuse the last transaction — application 16

Selecting a suggested general title fills amount, currency, account, merchant, place, notes, tags and receipt-provenance flag from that title's latest occurrence. Latest means greatest occurrence instant, with row ID as a deterministic tie-breaker; a later-inserted older transaction does not win. The draft's selected date and identity remain unchanged. Typing alone and suggestions in other fields retain their existing behavior. Template reads go through the finance capsule and do not modify existing transactions.

Four targeted FinanceAccountsTest cases passed, including complete template field equality, latest-occurrence selection, preserved date/identity and saving a separate new record. Debug/QA builds passed. Pixel QA selected a synthetic 02:30 transaction, retained the new draft's 02:47 date and saved it as a separate row; amount, account and receipt flag were copied and the original remained intact. No real data was used in QA.

Pixel and TCL main packages were updated in place from 15 to 16; installed version and successful launch were verified on both. The isolated Pixel QA package was removed. No database schema change or roadmap advancement.
