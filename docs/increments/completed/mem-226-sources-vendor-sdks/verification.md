# Verification

Design: [design.md](design.md). Plan: [plan.md](plan.md).

## Staging, 2026-10-04

Read from the worker log and the database of staging, and one synchronization of every source started through the API
as a Tenant administrator (a temporary Playwright spec, deleted afterwards).

Staging has ten Google Drive sources and no SharePoint source.

### Google Drive on Google's API clients (release `6bee0145`)

| | Release `0e036e91` (hand-written client) | Release `6bee0145` (Google's clients) |
| --- | --- | --- |
| Synchronizations compared | the scheduled ones between 12:09 and 13:54 UTC | one started for each source at 14:20 UTC, all accepted (202) |
| Sources that succeeded | 9 of 10 | 9 of 10 |
| Sources that completed with errors | 1: three files scanned, two unchanged, one failed | the same source, the same counts |
| Files scanned per source | 1 to 6 | the same |
| New warnings or errors about Drive in the worker log | – | none |

The one failing file fails on both releases (`SOURCE_ACQUISITION_INTERNAL`, an `IllegalArgumentException` in
`GoogleDriveSyncAdapter`, also in the 12:03 UTC run before the first deployment of the day). It is not caused by this
increment and its cause is not established.

What this exercised against Google: the refresh-token grant, listing a folder, file metadata and permissions.

### Not verified

- **Google Drive content**: no file had changed, so nothing was acquired on either release. The media download at
  its new path, a Docs and a Sheets snapshot, a Slides export and the service-account grant have run only against
  the local server of the tests. A changed file in a synchronized folder is the check.
- **The Google account consent**: not run on staging.
- **SharePoint on the Microsoft Graph SDK**: not run against any Tenant. Staging has no SharePoint source.
