# Soldi transaction photos

## Current storage contract

A transaction photo is a `finance_attachments` row; the image bytes are not stored in SQLite. The original image may live behind a persisted Android SAF `content://` URI or at a remote HTTPS URL. The database stores only the transaction link, attachment kind, URI/URL, MIME metadata and timestamps.

Remote storage is provider-agnostic. PersonalHub must not make Imgur, Telegram, or any other image-sharing service the canonical archive. A future object-storage integration may upload originals, but its provider and credentials are separate from the finance schema.

## Fast ledger previews

Soldi uses Coil for image loading. Ledger rows request only the displayed preview size; Coil performs downsampling and uses its memory/disk caches. The original can therefore remain on SAF or remote storage without making normal list scrolling depend on repeatedly decoding the full-resolution file.

Transaction rows render the first photo attachment as a square preview. `ContentScale.Crop` is presentation-only: it center-crops inside the square while leaving the original image untouched. Manual preprocessing in Google Photos is not required. Non-photo transactions retain the existing symbolic leading icon.

Soldi Home exposes a 🔍 global search surface. Its filter is live, is not restricted to the currently selected month, and searches every persisted transaction field plus the resolved product/title, merchant, place, person, account, tags and attachment metadata. Search results open the canonical transaction editor.
This global Search is the single finance search surface; the older month-local inline search is removed to avoid two competing behaviors.

Inside Search, 📷 opens a photos-only gallery. The gallery contains only square image previews, with no transaction captions or metadata mixed into the grid. Every stored photo is represented independently; tapping any preview opens the transaction that owns that attachment. The gallery reuses the same cached/downsampled image loader as the ledger rather than decoding full-resolution originals while scrolling.

Legacy local attachments whose MIME type is `image/*` remain recognized as photos. New image picks are tagged `PHOTO_URI`; explicitly supplied remote photo URLs are tagged `PHOTO_URL`.

## Local semantic retrieval

Soldi indexes transaction photos in the background after the transaction itself has already been saved. The original image still stays outside SQLite. `finance_photo_index` stores only the attachment identity, source hash, model identity/version, a compact 512-value embedding, optional local OCR text and status/error metadata.

The encoder is the quantized ONNX conversion of `onnx-community/TinyCLIP-ViT-8M-16-Text-3M-YFCC15M-ONNX`, pinned to revision `9463a9c508a344c837ffefe9d724f3827bf2dc79`. The 24,683,626-byte model and tokenizer are downloaded on demand into app-private storage; they are not packaged in the base APK. Both downloads are SHA-256 verified before use. The model card declares MIT and the upstream TinyCLIP license is MIT, copyright Microsoft Corporation; a copy of the notice is written beside the downloaded model.

The existing Soldi Search remains the only search surface. Normal transaction fields, tags and attachment metadata are combined with OCR text and local text-to-image similarity. Semantic-only matches show a visual-match score so the result is explainable rather than silently reordering the ledger.

“Trova questo oggetto” accepts either a temporary camera image or a gallery image, embeds it locally and compares it with the stored photo embeddings. It returns at most 10 candidates. The UI explicitly describes these as possible matches, never as a certain same-object identification. After the first model download and photo indexing, stored-history comparisons require no network access.

`finance_owned_items` is an optional layer over a source transaction. It stores an item UUID, name, source transaction and optional primary photo reference; price/date/merchant remain authoritative on the transaction. Deleting the primary photo clears only that reference, while deleting the source transaction deletes its owned-item rows.

## Performance and durability

The UI must remain usable with thousands of photographed transactions. Lists are lazy, previews are bounded, and originals stay outside SQLite. Deleting the local image cache must be safe: previews can be regenerated from the original URI/URL. A missing/unreachable original must degrade to a non-photo placeholder without breaking the transaction row.

SAF and remote URLs are references, not backups by themselves. Long-lived remote originals require a storage provider under the user's control or another durable backup policy.
