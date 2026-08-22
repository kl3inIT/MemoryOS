# MEM-12 verification

Date: 2026-08-21

## Implemented surface

- Added `invitation` as an eighth top-level closed Spring Modulith capability with allowed dependencies only on `identity` and `organization`.
- Added an Organization-owned public membership provisioner. Invitation never imports Organization persistence or writes membership tables directly.
- Added `V3__create_organization_invitations.sql` with digest-only secrets, lifecycle constraints, Organization/default-Workspace foreign keys, unique digest, and one open email key per Organization.
- Implemented owner create/list/rotate/revoke, recipient intake/resume, exact verified-email acceptance, atomic Actor binding/fixed memberships/consumption, session-state cleanup, and typed failure outcomes.
- Expanded the same-origin OpenAPI contract and regenerated the TypeScript client.
- Added the responsive `Admin Panel` → `People` experience, one-email dialog, copy/share result, durable lifecycle rows, rotate/revoke recovery, recipient landing, and plain-language failure states.
- Added production Nginx proxying, no-store/no-referrer invitation headers, gateway rate limiting for invitation intake and mutations, and a dedicated `/invite/` location with access logging disabled so capability secrets never enter gateway request logs.

## Persistence and concurrency evidence

`JdbcOrganizationInvitationServiceTest` passes the production Flyway SQL in H2 PostgreSQL mode through real Spring transaction proxies. It verifies owner authorization, normalization, digest-only storage, duplicate pending rejection, rotation, revocation, expiry/reissue, verified matching acceptance, mismatch/unverified rollback, existing-authority conflict, and concurrent one-winner acceptance.

`PostgresInvitationAcceptanceConcurrencyTest.concurrentAcceptanceSerializesOnInvitationAndCreatesOneMember` passes against digest-pinned PostgreSQL 17. It observes the second transaction waiting on the invitation row while the first holds the lock, then proves one accepted invitation, one new Actor/binding, and exactly one pair of fixed `MEMBER` memberships. This test exposed and corrected PostgreSQL JDBC's inability to infer `Instant` parameter types; production timestamp writes now use UTC `OffsetDateTime`.

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

- JetBrains inspections with warnings enabled are clean for every changed Java and YAML file after remediation of duplicated lifecycle code, redundant catch/parameters, record-pattern use, PostgreSQL timestamp binding, and test diagnostics.
- `ModulithArchitectureTest` and `CoreDependencyRulesTest` pass with the new capability and persistence ownership.
- `gradlew.bat clean check --no-daemon` passes the repository-wide gate.

## Keycloak desired-state evidence

- `sh -n infrastructure/keycloak/configure-memoryos-realm.sh` passes.
- A disposable Linux `kcadm` double exercised the complete existing-user reconciliation path. It verified `registrationAllowed`, email-as-username, login-with-email, duplicate-email rejection, required email verification, authenticated STARTTLS SMTP, and the verified initial-owner profile payload.
- Dummy operator, browser-client, and SMTP passwords were absent from script output. The captured secret-bearing payload and temporary double were deleted after assertions.
- Keycloak sends verification mail through its configured SMTP provider. MemoryOS runtime uses Spring Security's standard OAuth2 client for authorization-code/token exchange and contains no Keycloak Admin SDK, custom Admin REST client, or SMTP credentials.

## Remaining shared-runtime gate

The shared `memoryos` Keycloak realm currently reports:

```text
registrationAllowed=false
registrationEmailAsUsername=false
verifyEmail=false
smtpConfigured=false
```

The repository desired state for self-registration and verified email is complete. Applying it to the shared realm still requires concrete managed SMTP host/from/username/password values. Until those values are supplied, the implemented invitation flow is fully usable only for an existing verified local Keycloak account, and the no-operator account-creation happy path cannot be claimed. No fake provider or unverified-registration bypass was added.

The exact reviewed-head deployment, shared owner-to-recipient browser flow, pull request review, merge-SHA CI, and increment closure remain open.
