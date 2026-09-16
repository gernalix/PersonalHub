# PersonalHub Privacy Policy

**Effective date:** September 16, 2026

This policy applies to the Google Play distribution of PersonalHub (`com.gernalix.personalhub`). PersonalHub is a personal data and activity-management application. Its core design is local-first: information you enter is stored on your device unless you explicitly configure a feature that sends data elsewhere.

## Data PersonalHub can handle

Depending on the features you use, PersonalHub can store or process information such as:

- people and contact information you enter;
- places, visits, routes and foreground location information;
- timers, sessions, events and reminders;
- substance/intake and stock records;
- WordPulse text-entry/session metrics;
- finance, transaction and product records;
- photos or files you explicitly attach or import;
- app configuration, backup metadata and synchronization state.

The Google Play build does **not** request background location, call-log access, phone-state access, draw-over-other-apps access, or full-screen-intent permission. Those capabilities are excluded from the Play-distributed build.

## Location

PersonalHub can request approximate or precise foreground location when you use a location-dependent feature and grant the Android permission. The Google Play build does not request `ACCESS_BACKGROUND_LOCATION`.

## Local storage and backups

PersonalHub stores its application database and related state locally on your device. You can export or import the PersonalHub database using the app's backup controls. Android system backup is disabled for the application package; exports are created only through app functionality you invoke or configure.

## Optional synchronization

PersonalHub includes optional Datasette synchronization. It is off unless you configure it. If enabled, PersonalHub can send the current data set and subsequent changes to the HTTPS endpoint, database and table that you provide. The access token is treated as a credential and is not intentionally included in database exports or application logs.

Data sent to a server that you configure is also subject to that server's retention, access and privacy practices. PersonalHub does not control a third-party or self-hosted endpoint selected by you.

## External services

Some user-invoked features can contact external services needed to provide their requested functionality, such as mapping, place/address lookup or other network-backed lookups. Requests may necessarily expose ordinary network metadata such as your IP address to the service provider. PersonalHub does not intentionally send the app's complete local database to such services unless you explicitly configure synchronization as described above.

## Advertising, sale of data and developer analytics

PersonalHub is not designed to sell personal information. The current Google Play build does not include an advertising SDK or a developer-operated behavioral analytics backend.

## Permissions

PersonalHub requests Android permissions only for features that need them. Permission availability does not itself cause a feature to collect data; Android permission prompts and in-app actions control access. The Play build intentionally excludes permissions with elevated Google Play policy requirements when they are not required for its core store-distributed functionality.

## Security

PersonalHub uses Android application sandboxing for local data and HTTPS for the optional Datasette synchronization endpoint. No software or storage method can guarantee absolute security; keep your device and any configured synchronization endpoint appropriately secured.

## Retention and deletion

Local PersonalHub data remains on your device until you delete it through app/device controls, replace it through an import, clear the application's storage, or uninstall the app. Data copied to an export file remains until you delete that file. Data sent to a synchronization endpoint remains according to the policy and configuration of that endpoint.

PersonalHub does not provide a developer-hosted account system in the Google Play build, so there is no separate PersonalHub cloud account to delete.

## Children

PersonalHub is a general personal-organization application and is not specifically directed to children.

## Changes to this policy

This policy may be updated when PersonalHub's data handling or distributed feature set changes. The current version is published with the application's source repository.

## Contact

For privacy questions or requests concerning PersonalHub, use the project's public issue tracker:

https://github.com/gernalix/PersonalHub/issues
