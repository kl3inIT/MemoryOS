# Plan

- [x] Trace the Group detail failure to the client-side `edit` check and the association-derived scoped write rule.
- [x] Record the accepted authority model in [ADR 0011](../../decisions/0011-source-manager-group-attachment-authority.md).
- [x] Core: `manager_actor_id` migration with backfill, manager-based `SourceScopeSql`, delta-checked `replaceSourceGroups`, Group-authorized `removeGroupSource`, `assignSourceManager`, and creation without a Group.
- [x] API: `POST /api/sources/{sourceId}/manager`, `managerActorId` on `SourceSummary`, `removableSourceIds`, refreshed `openapi.yml`.
- [x] Web: removal through the Group command, optional groups at creation, the unattached-Source warning, and the administrator appointment section.
- [x] Update the Connector spec and matrix, README, ARCHITECTURE, roadmap and AGENTS.md.
- [x] Run the core, API and web gates for the changed surfaces.
- [x] Owner decision 2026-09-21: only the Source's manager detaches it from a Group; record [ADR 0013](../../decisions/0013-only-the-source-manager-detaches-sources.md), guard `removeGroupSource` and `removableSourceIds` with the scoped write rule, lock and explain other managers' Sources on Group detail.
- [ ] Owner acceptance on staging, including the reported Group detail removal and a second manager of the same Group being unable to detach another manager's Source.
- [ ] Decide whether appointing a new manager should pause Google Drive synchronization until the new manager reconnects their own Google grant (open gap in the design).

## Verification — 2026-09-16

- `./gradlew :core:test :api:test`: build successful, no failing class. This covers the rewritten authority tests:
  - `PostgresSourceLifecycleTest` (25 tests): a Source created globally stays read-only for a Group manager who manages every associated Group; `SYSTEM_ADMIN` appointment is required and rejects a candidate managing no ordinary Group (`SOURCE_MANAGER_NOT_ELIGIBLE`); the appointed manager renames and re-attaches but may not drop another manager's Group; a PUBLIC Source stays read-only for its manager; scoped creation is RESTRICTED, records its creator and may start with no Group; any Group's manager detaches shared, single-Group and PUBLIC Sources from their own Group while other Groups keep theirs; clearing the manager withdraws upload finalization mid-flight.
  - `GoogleDriveCredentialAuthorityTest` (44 tests): another manager's Group joining the Source leaves the recorded manager's credential actions intact, and clearing the manager withdraws them.
  - `SourceApiIntegrationTest` (26 tests): `removableSourceIds`, `204` detachment for both Sources, `403` for a non-administrator appointment, `SOURCE_MANAGER_NOT_ELIGIBLE`, appointment restoring `permissions.edit`, and scoped creation with no group returning `201` with `managerActorId`.
  - `OpenApiContractTest`: the committed contract matches, including the new path and `SourceSummary.managerActorId`.
- Web: client regenerated with `@hey-api/openapi-ts@0.99.0`; `tsc -b`, `oxfmt`, `oxlint --deny-warnings` and the full unit suite (51 files, 281 tests) pass, including the rewritten `source-groups-section.test.tsx` (8 tests) covering removal through the command, clearing the last association as the responsible manager, the unattached warning and the deleting-Source lock.

Not verified: staging, and any browser run of the administrator appointment section.

## Verification — 2026-09-21 (ADR 0013)

- `./gradlew :core:test --tests "io.memoryos.connector.*" :api:test --tests "io.memoryos.api.source.*" --tests "*OpenApiContractTest*"`: build successful; 265 core connector tests and 53 API source/contract tests, no failure or skip.
  - `PostgresSourceLifecycleTest.onlyTheResponsibleManagerDetachesASourceFromTheirGroup`: two managers of one Group; the one who did not create the Source gets no `removableSourceIds` and `SOURCE_NOT_FOUND` for managed, unmanaged and PUBLIC Sources, and the associations stay; the responsible manager detaches only their Source, only from a Group they manage, and attaches it again; global management detaches any Source.
  - `SourceApiIntegrationTest.onlyTheResponsibleManagerDetachesSourcesAndAdministratorsAppointThem`: `404 SOURCE_NOT_FOUND` for a Group manager who is not responsible, then `204` once `SYSTEM_ADMIN` appoints them.
  - `OpenApiContractTest`: `openapi.yml` regenerated with the new `removeGroupSource` description.
- Web: client regenerated with `@hey-api/openapi-ts@0.99.0` (description only); `tsc -b`, `oxfmt --check`, `oxlint --deny-warnings`, the i18n audit and the full unit suite (101 files, 476 tests) pass, including `source-groups-section.test.tsx` (9 tests) for the locked chip that names the responsible manager and the deleting-Source lock.

Not verified: staging or a browser run of Group detail with two managers.
