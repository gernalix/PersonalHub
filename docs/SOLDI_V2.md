# Soldi v2

Soldi v2 implements the approved PersonalHub finance UX while keeping the visual system original to PersonalHub. My Budget Book was used only as functional inspiration; no proprietary assets, branding, copied code, or pixel-identical screen reproduction are used.

## Ledger model

- Expenses and income are ordinary finance transactions.
- A transfer is one logical operation backed by two linked ledger legs. Same-currency transfers are neutral movement between accounts. Cross-currency transfers preserve both source and destination amounts plus optional quoted FX rate and fee metadata.
- The effective FX rate is derived from the actual received amount divided by the source amount; it is deliberately distinct from the quoted rate.
- Receipt imports create a non-posting macro entry whose children are the posting microtransactions. Expanding a macro in the transaction list reveals those children.
- Money calculations use `BigDecimal`; currencies are never summed into one authoritative total.

## People, categories, and tags

- Person selection autocompletes from the existing People/contact database while typing.
- The old Group concept is not part of Soldi v2. Multiple tags replace it.
- Categories are a separate hierarchical-text dimension (for example `Spesa › Supermercato`) and tags remain independent/multiple.

## Recurrences and reminders

- Recurrence is a rule that materializes ordinary transactions when due; it is not a separate posting transaction type.
- Monthly rules support a chosen day or the last Monday-Friday day of the month.
- Editing a future occurrence supports `only this` or `this and following` semantics.
- A reminder may belong to a recurring rule, but a one-off future transaction can also have an independent reminder.

## Attachments and OCR

- Transaction attachments store only URI/URL metadata in SQLite, not image/PDF blobs. This keeps `personalhub.db` and its automatic export compact.
- A local SAF URI depends on the underlying document still being available on that Android device/provider. For portable attachments prefer a stable external URL; a future sidecar-file export can make local documents portable without bloating SQLite.
- Receipt OCR remains a separate import workflow.
- The cross-currency transfer editor can run on-device OCR on a Revolut screenshot and attempt to fill source/target accounts, amounts, quoted rate, and fee. Users can review/edit the parsed result before saving.

## Screens

The approved screen set is implemented as PersonalHub-native Compose UI:

- Transactions: day grouping, balances by currency, logical transfers, expandable macros.
- Overview: current and projected month-end wealth grouped by currency, plus cash flow.
- Statistics: expense/category summaries per currency.
- Charts: category chart per selected currency.
- Calendar: each day shows that day's wealth in every available currency and marks projected recurrences.
- Accounts, categories, recurrences, add/edit transaction, transfer, recurrence editor, and view options.

Recurring-payment list icons intentionally use locally generated title monograms instead of third-party brand assets.
