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

The normal private `release` build remains available separately.

## Play policy surface

The Play-specific manifest overlay removes capabilities that are not required for the store-distributed core experience and would otherwise create elevated policy/review surface:

- background location;
- call-log access;
- phone-state access;
- draw-over-other-apps;
- full-screen-intent permission;
- call-state/overlay receivers tied to those capabilities.

Foreground approximate/precise location remains available for user-invoked location functionality. Exact scheduling continues to use `SCHEDULE_EXACT_ALARM`, which is user-controlled, rather than the restricted `USE_EXACT_ALARM` permission.

As of September 16, 2026, PersonalHub targets Android API 37, above the Google Play target-API requirement for new apps and updates. New Play releases use Android App Bundles (`.aab`).

## Privacy

Canonical privacy policy:

https://github.com/gernalix/PersonalHub/blob/main/docs/PRIVACY_POLICY.md

The same policy is reachable from PersonalHub Settings. Keep the policy and Play Console Data safety answers synchronized with the actual `play` variant whenever data handling changes.

## Automated remote checks

GitHub Actions performs Play preflight checks that do not require private signing material:

- compile/lint/unit-test the `play` variant;
- merge the Play manifest;
- verify that restricted permissions/components are absent from the merged Play manifest;
- verify package and target-SDK invariants.

Do not upload signing secrets to public-repository CI merely to build the final AAB.

## Local release gate

The final signed release gate requires local resources and therefore is intentionally not done in GitHub-hosted CI:

1. synchronize the canonical PersonalHub checkout;
2. verify canonical signing material without printing secrets;
3. build `bundlePlay` once;
4. inspect the produced AAB/merged manifest and signing identity;
5. install the AAB-derived APK set on an isolated emulator and perform a bounded smoke test;
6. retain the verified AAB for Play Console upload.

This gate belongs in the Codex roadmap because the keystore, Android SDK/emulator and canonical local runtime are not available to the remote chat.

## Play Console work

Play Console administration is not a Codex/local-code task. Before publishing, complete the current Play Console forms and assets as applicable, including:

- store listing text and graphics;
- Data safety;
- privacy-policy URL;
- content rating;
- target audience/content declarations;
- ads declaration;
- app access instructions if any feature becomes access-restricted;
- any permission declarations Play Console requests for the uploaded bundle.

Do not claim a policy declaration is complete until Play Console itself accepts it.
