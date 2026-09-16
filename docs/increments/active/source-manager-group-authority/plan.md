# Plan

- [x] Trace the Group detail failure to the client-side `edit` check and the association-derived scoped write rule.
- [x] Record the accepted authority model in [ADR 0011](../../decisions/0011-source-manager-group-attachment-authority.md).
- [x] Core: `manager_actor_id` migration with backfill, manager-based `SourceScopeSql`, delta-checked `replaceSourceGroups`, Group-authorized `removeGroupSource`, `assignSourceManager`, and creation without a Group.
- [x] API: `POST /api/sources/{sourceId}/manager`, `managerActorId` on `SourceSummary`, `removableSourceIds`, refreshed `openapi.yml`.
- [x] Web: removal through the Group command, optional groups at creation, the unattached-Source warning, and the administrator appointment section.
- [x] Update the Connector spec and matrix, README, ARCHITECTURE, roadmap and AGENTS.md.
- [x] Run the core, API and web gates for the changed surfaces.
- [ ] Owner acceptance on staging, including the reported Group detail removal.
- [ ] Decide whether appointing a new manager should pause Google Drive synchronization until the new manager reconnects their own Google grant (open gap in the design).

## Verification — 2026-09-16

- `./gradlew :core:test :api:test`: build successful, no failing class. This covers the rewritten authority tests:
  - `PostgresSourceLifecycleTest` (25 tests): a Source created globally stays read-only for a Group manager who manages every associated Group; `SYSTEM_ADMIN` appointment is required and rejects a candidate managing no ordinary Group (`SOURCE_MANAGER_NOT_ELIGIBLE`); the appointed manager renames and re-attaches but may not drop another manager's Group; a PUBLIC Source stays read-only for its manager; scoped creation is RESTRICTED, records its creator and may start with no Group; any Group's manager detaches shared, single-Group and PUBLIC Sources from their own Group while other Groups keep theirs; clearing the manager withdraws upload finalization mid-flight.
  - `GoogleDriveCredentialAuthorityTest` (44 tests): another manager's Group joining the Source leaves the recorded manager's credential actions intact, and clearing the manager withdraws them.
  - `SourceApiIntegrationTest` (26 tests): `removableSourceIds`, `204` detachment for both Sources, `403` for a non-administrator appointment, `SOURCE_MANAGER_NOT_ELIGIBLE`, appointment restoring `permissions.edit`, and scoped creation with no group returning `201` with `managerActorId`.
  - `OpenApiContractTest`: the committed contract matches, including the new path and `SourceSummary.managerActorId`.
- Web: client regenerated with `@hey-api/openapi-ts@0.99.0`; `tsc -b`, `oxfmt`, `oxlint --deny-warnings` and the full unit suite (51 files, 281 tests) pass, including the rewritten `source-groups-section.test.tsx` (8 tests) covering removal through the command, clearing the last association as the responsible manager, the unattached warning and the deleting-Source lock.

Not verified: staging, and any browser run of the administrator appointment section.
