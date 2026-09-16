# PersonalHub Google Play release

This document describes the canonical Google Play distribution path. It is intentionally separate from the private/full PersonalHub build.

## Distribution variant

Google Play uses the `play` build type.

```bash
./gradlew --no-configuration-cache :app:bundlePlay
```

Expected bundle output:

```text
app/build/outputs/bundle/play/app-play.aab
```

The `play` variant inherits the optimized/minified `release` configuration and uses the same application ID, `com.gernalix.personalhub`. Signed artifact tasks require the canonical local signing configuration in `/home/daniele/.config/codex/secrets/android_signing.env`; signing credentials and the keystore must never be committed.

GitHub-hosted CI is allowed one deliberately narrow exception: it may run **only** `bundlePlay` with `-Ppersonalhub.allowUnsignedPlayBundle=true`. This produces the same minified/shrunk Play package for deterministic packaging checks without exposing private signing material. The opt-in is rejected for APK, install, connected-test, generic build/assemble, or any other signing-sensitive task.

The normal private `release` build remains available separately.

## Play policy surface

The Play-specific manifest overlay removes capabilities that are not required for the store-distributed core experience and would otherwise create elevated policy/review surface:

- background location;
- call-log access;
- phone-state access;
- draw-over-other-apps;
- full-screen-intent permission;
- call-state/overlay receivers tied to those capabilities;
- the background geofence receiver.

Foreground approximate/precise location remains available for user-invoked location functionality. Exact scheduling continues to use `SCHEDULE_EXACT_ALARM`, which is user-controlled, rather than the restricted `USE_EXACT_ALARM` permission.

As of September 16, 2026, PersonalHub targets Android API 37, above the Google Play target-API requirement for new apps and updates. New Play releases use Android App Bundles (`.aab`).

### Android 17 location-button policy

Because PersonalHub targets API 37, keep the Android 17 foreground-location minimum-scope policy on the release checklist. Google has announced that transactional one-time precise-location use cases on apps targeting Android 17+ must move to the Android location button; enforcement is scheduled for January 27, 2027, with the precise-location Play declaration becoming available in November 2026.

The current September 2026 Play release can still use the existing foreground precise-location flow. Before the January 2027 enforcement date, either:

- migrate transactional precise-location actions to the Android location button and use the corresponding manifest restriction; or
- retain standard `ACCESS_FINE_LOCATION` only if PersonalHub has a core persistent precise-location use case that can be justified in the Play declaration.

Do not reintroduce background location merely to avoid this migration.

## Privacy

Canonical privacy policy:

https://github.com/gernalix/PersonalHub/blob/main/docs/PRIVACY_POLICY.md

The same policy is reachable from PersonalHub Settings. Keep the policy and Play Console Data safety answers synchronized with the actual `play` variant whenever data handling changes.

## Automated remote checks

GitHub Actions performs all Play checks that do not require private signing material or a real Android runtime:

- compile/analyze the `play` variant through `lintPlay`;
- merge the Play manifest;
- build the actual minified/shrunk Play AAB without signing secrets;
- verify that restricted permissions/components are absent from the merged Play manifest;
- verify package and target-SDK invariants;
- verify that exactly one readable AAB is produced and that it stays below the repository's 200 MiB preflight ceiling;
- fail closed if native `.so` libraries appear, so 16 KiB page-size compatibility cannot become an unreviewed release regression.

Common unit/integration testing stays in the repository-wide CI gate rather than being duplicated by this Play-specific workflow.

Do not upload signing secrets to public-repository CI. The CI AAB is a packaging/preflight artifact only; it is not the release artifact uploaded to Play.

## Signing continuity and Play App Signing

Google Play App Signing is mandatory for new Play apps. Before the first production upload, decide the certificate strategy deliberately. PersonalHub already has private/sideloaded builds using the canonical signing material. If Play-distributed builds must update those existing installations in place, the Play app-signing certificate must remain compatible with the certificate expected by those installed packages. Do not accept a new incompatible Play signing identity accidentally.

The local release gate records the current signing certificate identity without exposing private key material so it can be compared with the Play App Signing setup.

## Local release gate

The final signed release gate requires local resources and therefore is intentionally not done in GitHub-hosted CI:

1. synchronize the canonical PersonalHub checkout after all repository-wide CI work is complete;
2. verify canonical signing material without printing secrets;
3. build `bundlePlay` once with the canonical signing identity;
4. inspect the produced AAB/manifest and public certificate fingerprint;
5. install an APK set derived from that exact AAB on the isolated `Pixel_8a` emulator and perform a bounded smoke test;
6. retain the verified AAB and checksum for Play Console upload.

This gate belongs in the Codex roadmap because the keystore, Android SDK/emulator and canonical local runtime are not available to the remote chat.

## Play Console work

Play Console administration is separate from repository readiness. Before publishing, complete the current Play Console forms and assets as applicable, including:

- store listing text and graphics;
- Data safety;
- privacy-policy URL;
- content rating;
- target audience/content declarations;
- ads declaration;
- app access instructions if any feature becomes access-restricted;
- Play App Signing setup and certificate review;
- any permission declarations Play Console requests for the uploaded bundle.

Do not claim a policy declaration is complete until Play Console itself accepts it.
