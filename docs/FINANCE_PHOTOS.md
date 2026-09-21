# Soldi transaction photos

## Current storage contract

A transaction photo is a `finance_attachments` row; the image bytes are not stored in SQLite. The original image may live behind a persisted Android SAF `content://` URI or at a remote HTTPS URL. The database stores only the transaction link, attachment kind, URI/URL, MIME metadata and timestamps.

Remote storage is provider-agnostic. PersonalHub must not make Imgur, Telegram, or any other image-sharing service the canonical archive. A future object-storage integration may upload originals, but its provider and credentials are separate from the finance schema.

## Fast ledger previews

Soldi uses Coil for image loading. Ledger rows request only the displayed preview size; Coil performs downsampling and uses its memory/disk caches. The original can therefore remain on SAF or remote storage without making normal list scrolling depend on repeatedly decoding the full-resolution file.

Transaction rows render the first photo attachment as a square preview. `ContentScale.Crop` is presentation-only: it center-crops inside the square while leaving the original image untouched. Manual preprocessing in Google Photos is not required. Non-photo transactions retain the existing symbolic leading icon.

Soldi Home exposes a 🔍 global search surface. Its filter is live, is not restricted to the currently selected month, and searches every persisted transaction field plus the resolved product/title, merchant, place, person, account, tags and attachment metadata. Search results open the canonical transaction editor.

Inside Search, 📷 opens a photos-only gallery. The gallery contains only square image previews, with no transaction captions or metadata mixed into the grid. Every stored photo is represented independently; tapping any preview opens the transaction that owns that attachment. The gallery reuses the same cached/downsampled image loader as the ledger rather than decoding full-resolution originals while scrolling.

Legacy local attachments whose MIME type is `image/*` remain recognized as photos. New image picks are tagged `PHOTO_URI`; explicitly supplied remote photo URLs are tagged `PHOTO_URL`.

## Visual retrieval contract

Semantic visual retrieval is intentionally separate from thumbnail rendering and is not implemented by the basic photo-list UI.

The target flow is:

1. When a transaction photo is attached, an on-device image encoder creates a compact embedding and optional OCR/labels in the background.
2. The model must not be bundled into the base APK when that would materially increase APK size; prefer an on-demand model download into app-private storage.
3. Model code and weights must have a license suitable for product use. Do not make Apple MobileCLIP weights a hard dependency while their model-weight license is restricted to research use.
4. A future “Trova questo oggetto” action accepts a new camera/gallery image, embeds it, compares it with saved photo embeddings using cosine similarity, and returns a ranked shortlist (normally 5–10 transactions). It must not claim identity from the top score alone.
5. Retrieval must tolerate normal changes in pose, background and framing; the user must not need to recreate the original photograph.
6. The searchable index belongs in lightweight structured data. Querying historical photos must not require downloading or re-encoding every original image.
7. Text/OCR/category signals may be combined with visual similarity so searches such as “giubbotto” can narrow the visual gallery.

A later implementation may store an optional focal point for the square preview. It must store coordinates/alignment metadata rather than a second permanently cropped original.

## Performance and durability

The UI must remain usable with thousands of photographed transactions. Lists are lazy, previews are bounded, and originals stay outside SQLite. Deleting the local image cache must be safe: previews can be regenerated from the original URI/URL. A missing/unreachable original must degrade to a non-photo placeholder without breaking the transaction row.

SAF and remote URLs are references, not backups by themselves. Long-lived remote originals require a storage provider under the user's control or another durable backup policy.
