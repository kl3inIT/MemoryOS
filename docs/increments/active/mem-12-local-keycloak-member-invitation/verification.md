# MEM-12 verification

Date: 2026-08-21

## Implemented surface

- Added `invitation` as an eighth top-level closed Spring Modulith capability with allowed dependencies only on `identity` and `organization`.
- Added an Organization-owned public membership provisioner. Invitation never imports Organization persistence or writes membership tables directly.
- Added `V3__create_organization_invitations.sql` with digest-only secrets, lifecycle constraints, Organization/default-Workspace foreign keys, unique digest, and one open email key per Organization.
- Implemented owner create/list/rotate/revoke, recipient intake/resume, exact verified-email acceptance, atomic Actor binding/fixed memberships/consumption, session-state cleanup, and typed failure outcomes.
- Registered identity, organization, and invitation implementations through component scanning; removed static persistence factories, forwarding API beans, the redundant invitation nonce, and unused secret-version state. Worker scanning is limited to its deployable package so it does not instantiate API-only JDBC capabilities.
- Flattened public contract enums and records into top-level types. Invitation and initial Organization bootstrap now keep transactional orchestration in `application` and JDBC SQL/row mapping in `persistence`; Identity's existing adapters remain persistence-only.
- Expanded the same-origin OpenAPI contract and regenerated the TypeScript client.
- Added the responsive `Admin Panel` → `People` experience, one-email dialog, copy/share result, durable lifecycle rows, rotate/revoke recovery, recipient landing, and plain-language failure states.
- Added production Nginx proxying, no-store/no-referrer invitation headers, separate gateway rate limits for intake and mutations, and a dedicated `/invite/` access log that emits only a static redacted path while retaining status telemetry.

## Persistence and concurrency evidence

`DefaultInvitationServiceTest` passes the production Flyway SQL in H2 PostgreSQL mode through real Spring transaction proxies. It verifies owner authorization, normalization, digest-only storage, duplicate pending rejection, rotation, revocation, expiry/reissue, verified matching acceptance, mismatch/unverified rollback, existing-authority conflict, and concurrent one-winner acceptance.

`PostgresInvitationAcceptanceConcurrencyTest.concurrentAcceptanceSerializesOnInvitationAndCreatesOneMember` passes against digest-pinned PostgreSQL 17. It proves one-invitation replay serialization, then drives two different invitations for one pre-bound identity and observes the second transaction waiting on the stable Actor row until the first membership grant commits. Exactly one invitation accepts that Actor, one remains pending, and exactly one fixed membership pair exists. The test also proves PostgreSQL uses `uq_organization_invitations_secret_digest` for digest lookup after the schema uses `VARCHAR(64)`.

## Browser and session evidence

`BrowserAuthenticationIntegrationTest` passes six real-HTTP scenarios against the Spring API composition, local standards-shaped OIDC provider, Flyway schema, and JDBC Spring Session.

The invitation success scenario proves:

- owner session creation through Authorization Code + S256 PKCE;
- same-origin guarded invitation creation;
- capability-link intake with `Cache-Control: no-store` and `Referrer-Policy: no-referrer`;
- redacted session continuation and recipient landing context;
- verified matching email plus exact issuer/subject;
- fixed Organization/default-Workspace `MEMBER` grants and accepted lifecycle state;
- authenticated `/api/identity/me` with the invited `ActorId`;
- absence of provider token markers and serialized invitation continuation from the final session.

The mismatch scenario proves an email mismatch redirects to the recipient recovery surface, invalidates the partial session, returns `401` from `/api/identity/me`, creates no binding, and leaves the invitation pending.

## Frontend evidence

- `pnpm check` passes generated-client freshness, container-image pin, zero-warning lint, formatting, TypeScript, 6 unit tests, route-tree freshness, and the production build.
- `pnpm test:e2e` passes 9/9 Chromium contracts, including owner create/rotate/revoke and recipient landing/failure recovery.
- Live `playwright-cli` inspection verified the People page and invitation dialog at desktop width with the 240px administration sidebar, plus the People page and recipient landing at 390 × 844. Navigation selection, modal focus, empty state, copy-link composition, and mobile layout were visually confirmed.
- `memoryos-web:mem12` builds from the production Dockerfile. `nginx -t` passes the generated image configuration; the expected non-root `user` warning is retained because the runtime intentionally starts as UID 101.

## Static and repository gates

- JetBrains inspections with warnings enabled report no errors. The only remaining weak warnings are IntelliJ's header-name registry rejecting the standard `Referrer-Policy` header and the intentional custom `X-MemoryOS-CSRF` header; both values are exercised by browser integration tests.
- Focused core architecture, Invitation, Organization, API startup/browser-authentication, and worker startup tests pass. Worker explicitly excludes JDBC datasource auto-configuration because the shared core starter is present but no worker persistence runtime path exists.
- `ModulithArchitectureTest` and `CoreDependencyRulesTest` pass with the application/persistence package boundaries.
- `gradlew.bat clean check --no-daemon` passes the repository-wide gate.

## Keycloak desired-state evidence

- `sh -n infrastructure/keycloak/configure-memoryos-realm.sh` passes.
- A disposable Linux `kcadm` double exercised the complete existing-user reconciliation path. It verified `registrationAllowed`, email-as-username, login-with-email, duplicate-email rejection, required email verification, authenticated STARTTLS SMTP, and the verified initial-owner profile payload.
- Dummy operator, browser-client, and SMTP passwords were absent from script output. The captured secret-bearing payload and temporary double were deleted after assertions.
- Keycloak sends verification mail through its configured SMTP provider. MemoryOS runtime uses Spring Security's standard OAuth2 client for authorization-code/token exchange and contains no Keycloak Admin SDK, custom Admin REST client, or SMTP credentials.

## Shared existing-user runtime evidence

On 2026-08-22, source head `a2a70736f5f47467b36dc50bdcb466dd5e2eb3cb` ran locally against the shared PostgreSQL 18.4 database and public `memoryos` Keycloak realm through an SSH tunnel. Flyway applied `V3__create_organization_invitations.sql`; startup initially rejected a stale local deployment change reference, then replayed successfully after local runtime metadata was synchronized with the deployed server configuration. `/actuator/health` returned `UP`.

The real browser flow then proved:

- the existing verified initial owner authenticated through Authorization Code + S256 PKCE;
- the owner created an invitation and rotated it, invalidating the first capability secret;
- a temporary verified local Keycloak recipient completed its required profile, consumed the rotated invitation, and landed authenticated on `New Session`;
- persistence checks returned true for accepted digest-only Invitation state, exact issuer binding, active Organization `MEMBER`, active default-Workspace `MEMBER`, active Actor session, and absence of an Invitation session attribute;
- the temporary Keycloak user, invitation, memberships, binding, Actor, and session were deleted after evidence capture; the API, Vite server, and SSH tunnel were stopped.

The invited member also exposed a separate shell defect: owner labeling and administration navigation remain visible even though owner-only APIs correctly deny the member. [MEM-19](https://linear.app/memory-os/issue/MEM-19/hide-owner-only-administration-from-invited-members) tracks that UI/authority-context cutover.

## Remaining shared-runtime gate

The shared `memoryos` Keycloak realm currently reports:

```text
registrationAllowed=false
registrationEmailAsUsername=false
verifyEmail=false
smtpConfigured=false
```

The repository desired state for self-registration and verified email is complete. Applying it to the shared realm still requires concrete managed SMTP host/from/username/password values. Until those values are supplied, the implemented invitation flow is fully usable only for an existing verified local Keycloak account, and the no-operator account-creation happy path cannot be claimed. No fake provider or unverified-registration bypass was added.

PR #13 reviewed head `b95a2c1c1876c8c389c534a8d15da8f3c43d46b3` merged as `1318008496091c8a8afad474a4dd519bff530cb5`; exact merge-SHA CI run `32556578404` passed. Only the no-operator self-registration/email-verification path and final increment closure remain open.
