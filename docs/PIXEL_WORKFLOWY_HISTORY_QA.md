# Pixel RDC gate: Workflowy and shared History/Search

Run this gate only on the isolated `com.gernalix.personalhub.qa` package built from the same revision as the test APK. The host stage prepares the artifacts and tests; RDC performs the physical Pixel run later. Preserve the user's main package and data. Record each assertion as PASS/FAIL with a screenshot or test output. Stop at the first failure.

| ID | Action on isolated QA package | Required observation |
| --- | --- | --- |
| QA01 | Enable Workflowy, link an unlinked entity with `Link node (manual)` or `Link node (auto)`, then open History/Search. | One human Workflowy link action row; no resource/context bookkeeping rows. |
| QA02 | Create and edit a People contact, then search both old and new names. | One row per action with readable person name and before → after. |
| QA03 | Create one Timer session; inspect History/Search. | One session creation row despite derived writes. |
| QA04 | Create and assign a tag. | One logical row per action; no standalone position, usage count, binding, or provenance rows. |
| QA05 | Record and edit a Substances intake. | Readable substance, dose and local time; no raw epoch, SQL column, ID, or encoding. |
| QA06 | Expand a grouped reversible action, then use Undo. | Undo is enabled only after a safe preview; one compensating logical action reverses the whole edit. Stale/partial cases stay disabled. |
| QA07 | Inspect the QA01–QA06 rows and search results. | No raw IDs/UUIDs, table or row names, backend payloads, `Stock Current`, `Timestamp Utc`, `Entity Kind`, `Position`, `Provenance`, or `Usage Count`. |
| QA08 | Compare the History/Search header to the former layout. | Compact title, date fields, module control and search; results visible without a header occupying about 40% of the screen. |
| QA09 | Open the module selector and scroll through every module. | Complete names are visible without horizontal clipping; multiple modules can be selected. |
| QA10 | Inspect actions from today, yesterday and an older date. | Sections read Today/Yesterday/localized date (Oggi/Ieri in Italian). |
| QA11 | Change date, module and live query filters; search both before and after values. | Results update without a submit action; reset appears only for changed filters. |
| QA12 | Repeat search across Workflowy, People, Timer, Tags and Substances. | The same shared screen shows the complete corpus with Git Data enabled; repeat with Git Data disabled to confirm the activity-log fallback. |
| QA13 | Link a Workflowy node to one Places alert and one Timer alert. Fire both, including a Timer random alert after restart, then tap their notifications. Repeat with Workflowy OFF. | Each linked alert opens its node directly while ON; OFF follows the ordinary alert tap behavior. |

Run `com.gernalix.personalhub.HubHistorySearchQaDeviceTest` against the `.qa` package for the automated screen, filter and compensating Undo checks. The Workflowy remote actions need a configured test account and RDC observation. Confirm `Open node`, `Delink node`, `Delete node` confirmation, remote failure retention, gate OFF/ON persistence, and legacy duplicate handling before marking QA01 complete.
