# Combined MEM-55 / MEM-36 verification

Evidence was collected on 2026-09-06 in the isolated Orca worktree `mem-55-users-management`, branch `kl3inIT/mem-55-users-management`. The combined increment remains active.

## Status and limitations

**Current-tree qualification (2026-09-06 ownership transfer):** the earlier successful gates below precede source/test warning cleanup. Initially no Gradle or containers were started with less than 1 GiB available host RAM. The user subsequently explicitly authorized isolated container-backed verification. The post-cleanup security gate (26 tests), full repository gate, frontend checks and 20 browser tests have now passed. The [security-patterns review](../mem-36-iam-jpa/security-patterns-review.md) separates current coverage from provider/broker/session follow-ups.

## Post-cleanup security verification

On 2026-09-06 at 22:57 local time, `gradlew.bat :api:test --tests '*SessionSecurityIntegrationTest' --tests '*BearerAuthenticationIntegrationTest' --no-daemon --no-parallel --max-workers=1 --no-configuration-cache` passed in 1m 56s. Gradle used `-Xmx384m -XX:MaxMetaspaceSize=256m -XX:ActiveProcessorCount=2`; a local init script limited each Test task to one fork, 128–512 MiB heap, 256 MiB metaspace, two active processors and one cached Spring context. Results: 11 session tests and 15 bearer tests, zero failures/errors, with isolated Testcontainers PostgreSQL and the existing local OIDC HTTP fixture.

The expanded admission/revocation scenario proves that inflated realm/client roles and scopes do not grant IAM authority, bearer authentication creates no session, another Actor's browser cookie is not overwritten, an invalid bearer does not fall back to the cookie, and the same token loses Tenant authority after membership deactivation. Existing application logout, PKCE, invitation/profile provenance and denied-admission tests also passed. This is not new upstream broker, Keycloak revocation or absolute-session-lifetime evidence.

At 23:08 local time the checked-in wrapper `clean check --no-daemon --no-parallel --max-workers=1 --no-configuration-cache` passed in 10m 31s using the same bounded JVM/test settings: 23 tasks, 19 executed and four compilation cache hits. XML results contain 42 API, 20 connector, 105 core and 22 worker tests: 186 passed, zero failures/errors, and three opt-in `DoclingServeIntegrationTest` scenarios skipped (real text/table, OCR PDF and PPTX service cases). No new real Docling service evidence is claimed. Testcontainers PostgreSQL/Redis resources were reclaimed; the pre-existing `memoryos-ide-schema-build` container was preserved. JetBrains semantic inspection remains unavailable/deferred, not an IDE-clean claim.

With Node 24.19.0 and `NODE_OPTIONS=--max-old-space-size=512`, sequential `pnpm check:api`, `check:ci`, `lint`, `format:check`, `typecheck`, `test:unit --maxWorkers=1` and `check:routes` passed. All 11 files/52 unit tests passed. The generated API and route snapshots remained stable; production build and emitted-font checks passed. `pnpm test:e2e --workers=1` then passed all 20 Chromium tests in 1.2m, including Users recovery serialization, URL-backed directory state, session/error convergence and FILE setup/lifecycle. These browser tests use the repository's HTTP/UI fixtures; they are not new real-Keycloak or upstream-broker acceptance.

The user authorized multiple scoped commits and a push of the existing branch for Nhat's reference. The verified application tree was published at `6ab31ffad064ab3b06cb0313c3d3a4d2bb9796f4`; subsequent commits reconcile documentation without changing that application/test tree. MEM-55/MEM-36 remain unmerged; no PR, remote CI, deployment or provider acceptance is implied by local success. Linear issues MEM-25/36/55/59/68/69 are assigned to `nhudinhnhat2004`; MEM-65 authentication theme is assigned to Duc Anh (`anhnd05122004`). All seven remain In Progress. Maintained Linear architecture documents are detailed Vietnamese references with Mermaid diagrams; obsolete architecture documents are marked `NEED REMOVE` for user removal rather than treated as current implementation.

## Earlier implementation evidence

- **Final repository gate passed.** `./gradlew.bat clean check --no-daemon --no-parallel --max-workers=1` completed successfully in 7m 16s after all behavioral fixes and dependency cleanup: all four modules' test/check tasks ran. The invocation used `JAVA_TOOL_OPTIONS="-XX:ActiveProcessorCount=4 -Dspring.test.context.cache.maxSize=4"` and `MEMORYOS_OPENAPI_WRITE=false`. The obsolete migration-count assertion was removed without weakening document/data/schema checks.
- **JetBrains inspection was unavailable.** `get_project_modules` and `get_file_problems` repeatedly timed out, including a request with a shorter explicit timeout. No IDE-clean claim is made. Wrapper compilation, real PostgreSQL/Flyway/Hibernate startup, and runtime smoke are the fallback evidence.
- The runtime was isolated and loopback-only. It did not write to staging, deploy a shared environment, create a commit/PR, merge, or move either Linear issue to Done.
- Browser tabs and supervised services were stopped. All six isolated `iam-61ce3b9d-*` containers, their volumes and the isolated network were removed; subsequent container/network queries returned empty. The upload proof file, inspection manifest and both extracted worker runtime directories were removed. Sanitized ignored evidence and the shared wire contract remain available locally.

## Completed automated evidence

| Evidence | Observed result |
| --- | --- |
| Core, API, and worker wrapper compilation | `compileJava` and `compileTestJava` succeeded for all three modules. |
| Final backend repository gate | API, connector, core and worker `test`/`check` passed; 23 actionable tasks, 21 executed and 2 compilation tasks from cache. Includes architecture, PostgreSQL authorization/concurrency, migrations and the final regression fixes. |
| API application smoke | `ApiApplicationSmokeTest`: 3 tests, 0 failures, including actual `RANDOM_PORT` servlet startup and health. |
| Live API schema generation | `OpenApiContractTest`: 1 test, 0 failures, with real PostgreSQL, Flyway V13–V16, Hibernate schema validation, and live OpenAPI generation. |
| Final frontend gates after mounted-query fix | `pnpm format`, `lint`, `typecheck`, `test:unit --maxWorkers=2`, `check:api`, `check:ci`, `check:routes` and `format:check` passed. All 11 unit-test files / 52 tests passed; route drift verification also ran the production build and font-asset check. |
| Revision-only mounted-query regression | The new active private Source regression failed before the fix because the old Source stayed rendered, then passed after active queries were reset before query-cache removal. |
| Scoped manager removal regression | Peer-manager removal incorrectly completed before the fix; `PostgresGroupMembershipReplacementTest.scopedManagerCannotRemoveAnotherManagerButGlobalAdministrationCan` is covered by the passing final core suite. |
| Invitation-expiry acceptance regression | Expiry during acceptance previously leaked `IllegalStateException`; `DefaultInvitationServiceTest.expiryDuringAcceptanceReturnsNotAvailableAndRollsBackNewAuthority` is covered by the passing final core suite. |
| Source recovery regression | A recovered item previously left its historical error on the Source; `PostgresSourceLifecycleTest.recoveredAttemptsClearHistoricalErrorsWithoutHidingOtherItemFailures` passed after latest-per-item reconciliation and in the final core suite. |

The referenced regression files were checked in the current checkout:

- `core/src/test/java/io/memoryos/iam/application/PostgresGroupMembershipReplacementTest.java`
- `core/src/test/java/io/memoryos/iam/application/DefaultInvitationServiceTest.java`
- `core/src/test/java/io/memoryos/connector/application/PostgresSourceLifecycleTest.java`
- `web/src/features/identity/application-session-boundary.test.tsx`

## Isolated real runtime

The exercised topology used isolated loopback PostgreSQL 18.4, Keycloak, MinIO, Redis, Mailpit, the API and worker production profiles, and the Vite frontend. Repository PostgreSQL tests remain pinned to 17.11. The worker used Spring Boot tools extraction plus `lib/*` and `MEMORYOS_EXTRACTION_CLASSPATH`, matching the supported Dockerfile layout; an initial raw fat-jar fixture omitted the child extraction classpath and was corrected without changing extractor behavior.

Observed end-to-end behavior:

1. **Owner admission and authority.** Real OIDC Authorization Code + PKCE login admitted the configured owner. Current identity returned `IAM_ADMIN` plus its expanded implemented capabilities. Users showed the owner's `STANDARD` account type and Admin/Basic Group memberships.
2. **Invitation and onboarding.** The owner invited a manager through the real Users dialog. Keycloak delivered the activation email to Mailpit; a browser completed the email action, password/profile setup, `/invite/activate`, and OIDC admission. A Keycloak account without IAM admission received bearer `401`. The admitted Actor was active MEMBER, `STANDARD`, Basic-only, and had no capabilities. A second recipient completed the same authorized invitation/OIDC path.
3. **Groups and grants.** The owner created an ordinary Group in the UI, enabled and removed `GROUPS_READ`/`SOURCES_READ`, and observed API persistence. The scoped manager saw only the managed ordinary Group, not Admin/Basic, and saw no create, rename, delete, or grant controls. Ordinary membership add/remove worked through the UI. After the owner promoted the second member through the API, scoped peer-manager removal returned `403 IAM_ACCESS_DENIED` and the forbidden UI control was absent.
4. **Users projection and membership editing.** The owner added an ordinary Group through the Users editor while Basic remained intact. Group filtering placed `groupId` in the URL and returned the one matching row. Responsive Group-tag overflow rendered `+1`; at a 390-pixel viewport the document width remained 390 pixels.
5. **Scoped Source lifecycle.** The associated Source appeared for the scoped manager; an unrelated Admin-associated Source returned `404`. Browser upload through real MinIO succeeded, the worker indexed the TXT content, and operation polling returned `SUCCEEDED`. Browser reindex succeeded; after the recovery fix the Source was `ACTIVE` with `documentCount=1` and `errorCode=null`. The owner persisted an additional Source–Group association through the UI.
6. **Denied operations.** The scoped Actor received Users `403`, unrelated Source `404`, and `403` for Source creation/deletion/association replacement, IAM grant editing, and manager assignment.
7. **Post-provider reauthorization and revision invalidation.** After an authorized upload intent and provider PUT `200`, the owner removed the Actor's Source association. Finalize, Source detail, and existing operation polling each returned `404`; the Source list became empty. A foreground identity refresh retained the same capability tokens but returned a changed `authorizationVersion`; the mounted Source name/file was removed and the UI rendered Source unavailable.
8. **Lifecycle safeguards.** Owner deactivation returned `403 TENANT_MEMBER_OWNER_PROTECTED`; Admin deletion returned `403 IAM_GROUP_PROTECTED`. Ordinary-member deactivation returned `204`; the existing bearer lost Groups access with `403`, and the open browser session converged to not-provisioned. Reactivation restored scoped access without adding role-based grants.
9. **Read-only authority.** After manager status was removed and only `GROUPS_READ` was granted, identity reported global `GROUPS_READ` and no scoped capabilities. Group detail exposed no member mutation or Source controls and rendered no unauthorized Source-association error panel.

## Earlier MEM-55 runtime evidence retained

The earlier isolated MEM-55 runtime also exercised real Keycloak invitation delivery, recovery rotation/revocation, exact profile provenance, ActorId-only sessions, active/inactive membership, owner protection, bounded Users filtering, desktop/mobile layouts, and private-cache convergence.

That smoke exposed Keycloak dropping the undeclared `memoryos.provisioned` attribute. The production reconciliation helper was extracted and executed twice through the real Keycloak Admin CLI. It created one optional, single-valued marker with admin-only view/edit permissions while preserving other profile attributes. A subsequently created recipient retained the marker and could be revoked/re-invited with the same provider user ID; a legacy account without provenance remained fail-closed. This proves the helper behavior, not execution of the entire deployment reconciliation script or a shared deployment.

The later Users copy and Actions-column revisions were visually exercised at desktop and 390-pixel widths with controlled API responses. They removed redundant hierarchy, retained accessible status/count feedback, fixed the sticky Actions column at 4rem, kept row/header backgrounds aligned, wrapped invitation expiry text, restored focus after actions, and avoided document-wide mobile overflow. These UI-only checks did not rerun Keycloak or alter backend behavior.

## Finalization

Canonical architecture, contracts, matrices and both active increment plans are consolidated. Backend/frontend gates and isolated runtime verification passed; JetBrains semantic inspection remains unavailable. The successful backend run emitted upstream JVM/protobuf warnings and a shutdown-time telemetry-fixture connection warning, not test failures; this record does not claim warning-free execution. No deployment, commit, PR, merge or Linear closure was performed. Both increments remain active for the normal review/merge workflow.
