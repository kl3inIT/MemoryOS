# Verification

## Repository gate (2026-09-15)

`.\gradlew.bat clean check --continue` on `9aa58d3a` (Windows host, Docker Desktop Testcontainers) finished with `BUILD SUCCESSFUL` in 7m19s.

| Module | Tests | Failed | Skipped |
|---|---:|---:|---:|
| core | 563 | 0 | 1 |
| connector | 121 | 0 | 4 |
| api | 145 | 0 | 4 |
| worker | 27 | 0 | 0 |

The skipped tests are the existing opt-in measurement and live-provider tests.

`pnpm check` in `web` passed after the web changes. It covers the contract drift check, i18n audit, oxlint, oxfmt, typecheck, unit tests and the route check.

## Tests that carry the contract

- `SourceAccessModesMigrationTest`: V61 renames RESTRICTED to PRIVATE, accepts only PUBLIC/PRIVATE/SYNC, and makes `access_type` on Drive selection operations nullable.
- `SourceSyncAccessTest`: the retained successful snapshot drives both reads and index tokens. It covers:
  - user, discoverable domain, non-discoverable domain, `anyone`, group, deleted and expired grants;
  - no snapshot, a failed-only snapshot, and a retained snapshot after a failed attempt;
  - revision bumps and a PUBLIC FILE origin;
  - switching to PRIVATE or PUBLIC;
  - reader tokens only for verified emails of active members;
  - parity between `readableDocuments` and `documentAccess` for every case.
- `GoogleDriveCredentialAuthorityTest`: Drive creation defaults to Auto Sync, keeps the requested mode, and rejects PUBLIC from a scoped manager.
- `SourceApiIntegrationTest`: FILE creation rejects SYNC with 400. A global manager can switch a Drive Source between SYNC, PUBLIC and PRIVATE. SYNC on a FILE Source returns 409.
- `SearchIndexWorkIntegrationTest`: `GoogleDriveAclChanged` enqueues an `ACCESS` operation for a SYNC Source and nothing for a PRIVATE one.
- `source-summary-card.test.tsx` (web): the Drive access note follows the mode — Auto Sync explains per-file Google Drive access, Private keeps the group wording, Public adds none.

## Live local runtime (2026-09-15)

A local API, worker and browser bundle ran against a fresh Arconia PostgreSQL migrated through V61. The owner signed in through the remote Keycloak and authorized Google Drive; no staging permission was changed and no email address is recorded here.

| Step | Observed |
|---|---|
| Create a Drive Source over the `4. YOUNGXV AUTO` fixture with the default mode | Pair stored with `access_type='SYNC'`; selection operation SUCCEEDED |
| First sync | 12 connector items; 12 documents ELIGIBLE and searchable; 12 `INDEX` operations SUCCESS |
| Retained permission snapshots | 13 rows, all with `observation_revision > 0` |
| Provider grants against the owner's verified login email | 0 files granted to that address, 0 by domain, 0 through `anyone`; 3 distinct user grantees on the fixture |
| Owner Search in Auto Sync (`YXA`, `YOUNGXV`, `giới thiệu`) | HTTP 200, 0 results and `totalResults=0` for each query |

The owner's verified MemoryOS login email is not one of the Drive grantees, so Auto Sync correctly returns nothing for that reader while the same Source holds 12 searchable documents. This is the ADR 0011 rule observed end to end on the real provider.

### Defect found and fixed

The Source detail summary still showed the pre-MEM-105 sentence "Document access follows this Source's MemoryOS groups, not Google Drive file permissions." under every Google Drive access badge, including Auto Sync, where it states the opposite of the enforced rule. `SourceSummaryCard` now selects the note from the mode and says nothing for a Public Drive Source; `source-summary-card.test.tsx` covers the three cases.

### Still open

A second reader was not exercised: the local Tenant had one member, and inviting another account plus matching it to a fixture grantee needs the owner to act in the browser. The two-account matrix (Auto Sync grantee versus non-grantee, Private with and without Group membership, Public) therefore rests on `SourceSyncAccessTest` until that live run happens.
