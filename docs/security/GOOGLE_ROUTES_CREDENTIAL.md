# Google Routes credential custody

PROMPT_ID=864209, P83 preparation. The route-distance capsule owns a dedicated Android Keystore AES/GCM alias and an atomic encrypted file in `Context.noBackupFilesDir`. It does not read or write Room, shared preferences, exports or profile data. Application package/certificate headers are resolved from the host application rather than the library ID.

## Migration input

The explicit import input is `google-routes.pending` inside that same private no-backup directory. Only the application UID may own it; it must be a regular, single-link file with mode 0600, containing exactly one ASCII key (16–512 characters, letters/digits/underscore/hyphen, no newline). The importer opens it without following symlinks and checks inode identity. It writes authenticated ciphertext with mode 0600, verifies readback, then removes the input. On failure it preserves the input and never uses the legacy key. A verified import left pending after interruption can be retried.

For the canonical debug distribution, staging must use the verified device and existing supported `run-as` access to the application UID, streaming the value on stdin. Do not put a key in command arguments, logs, a repository, a screenshot or a report. Verify the actual packaged debug flag/package and current device exclusivity before staging; do not root a device or bypass `run-as` denial. No new exported component is introduced. Non-debug distributions need a supported in-app provisioning path before this method can be claimed available.

Before replacing an already configured credential, preserve its encrypted state privately and verify the rollback provider remains available. Stage only an explicitly approved value from its native provider; do not rotate a provider as a side effect of staging. Trigger resolution through the normal Routes consumer only after the required device gate. Confirm that the input is absent and ciphertext/readback is healthy without exporting the plaintext.

## Consumer resolution

A present configured provider always wins. A broken ciphertext, missing Keystore key, dangling encrypted-state symlink, pending import with unsafe permissions or interrupted first write blocks credential resolution. HttpGoogleRoutesClient becomes unconfigured instead of silently sending the legacy key. Only an entirely unconfigured migration state may use the existing BuildConfig credential; that compatibility path remains until the coordinated cutover proves consumers ready. The new Routes credential must never enter BuildConfig or the final APK.

## Acceptance boundaries

Synthetic JVM tests cover authenticated encryption, random IVs, corrupted data, wrong keys, validation and no fallback. Isolated emulator tests cover the actual Android Keystore, restart/readback, permissions, symlink rejection, missing keys and import recovery. These tests do not prove Google endpoint enforcement or production provisioning.

Before provider activation: separate Places/Routes keys, actual final-artifact package and signing certificate, current provider usage and legacy consumers, explicit Google rotation authorization, and positive/negative Google restrictions tests. Final PersonalHub APK validation, install and delivery remain required. This preparation does not establish P83 PASS or public readiness.

Google security guidance: https://developers.google.com/maps/api-security-best-practices

## Shared Places/Routes adapter

GoogleCredentialStore is the single Google-services custody implementation. Routes keeps its previous alias and filenames; Places uses `personalhub.luoghi.places` and `google-places.enc` / `google-places.pending`. Their values and encryption keys are independent. AddressAutocompleteRepository obtains the Places credential through this adapter; it never silently falls back when its configured provider is broken. An existing Keystore alias without its ciphertext also counts as broken configured state.

During a Places cutover, restart the application process before SDK acceptance. GooglePlacesLocationProvider caches a PlacesClient and initializes the SDK only once per process; importing a value alone does not replace an already initialized SDK key. Preserve both legacy consumers until real service checks and final delivery have passed. A Places/Routes value must be staged only from its approved native reference, never copied between service kinds as an automatic fallback.
