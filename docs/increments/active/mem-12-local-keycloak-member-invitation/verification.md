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
- Added the responsive `Admin Panel` → `Invitations` experience, one-email dialog, copy/share result, durable lifecycle rows, rotate/revoke recovery, recipient landing, and plain-language failure states.
- Added production Nginx proxying, no-store/no-referrer invitation headers, separate gateway rate limits for intake and mutations, and a dedicated `/invite/` access log that emits only a static redacted path while retaining status telemetry.

## Persistence and concurrency evidence

`DefaultInvitationServiceTest` passes the production Flyway SQL in H2 PostgreSQL mode through real Spring transaction proxies. It verifies owner authorization, normalization, digest-only storage, duplicate pending rejection, rotation, revocation, expiry/reissue, verified matching acceptance, mismatch/unverified rollback, existing-authority conflict, and concurrent one-winner acceptance.

`PostgresInvitationAcceptanceConcurrencyTest.concurrentAcceptanceSerializesOnInvitationAndCreatesOneMember` passes against digest-pinned PostgreSQL 17. It proves one-invitation replay serialization, then drives two different invitations for one pre-bound identity and observes the second transaction waiting on the stable Actor row until the first membership grant commits. Exactly one invitation accepts that Actor, one remains pending, and exactly one fixed membership pair exists. The test also proves PostgreSQL uses `uq_organization_invitations_secret_digest` for digest lookup after the schema uses `VARCHAR(64)`.

## Browser and session evidence

`SessionSecurityIntegrationTest` passes six real-HTTP scenarios against the Spring API composition, local standards-shaped OIDC provider, Flyway schema, and JDBC Spring Session.

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

The owner session scenario also proves that POST `/logout` without the same-origin header returns `403` and leaves the session authenticated. The guarded request invalidates the JDBC session, removes the `SESSION` cookie, returns `204` with a Keycloak logout URL containing `client_id=memoryos-web` and the current registered post-logout origin, and makes `/api/identity/me` return `401`. No provider ID token is reintroduced to support logout.

PR #27 head `de260f96c8404132efa212165e704d8ea0ae33a7` was built into both staging API and web images; their OCI revision labels match that exact SHA and both containers became healthy. Chromium rendered the deployed authenticated shell and account popover with a separate danger-toned `Sign out` action. The public guarded POST returned `204`, expired the secure `SESSION` cookie, and returned the exact Keycloak `end_session_endpoint` with `client_id=memoryos-web` and `post_logout_redirect_uri=https://memoryos.72-62-193-33.nip.io/`.

## Frontend evidence

- `pnpm check` passes generated-client freshness, container-image pin, zero-warning lint, formatting, TypeScript, 8 unit tests, route-tree freshness, and the production build.
- `pnpm test:e2e` passes 10/10 Chromium contracts, including guarded account-menu sign-out, owner create/rotate/revoke, and recipient landing/failure recovery.
- Live `playwright-cli` inspection verified the Invitations page and invitation dialog at desktop width with the 240px administration sidebar, plus the Invitations page and recipient landing at 390 × 844. Navigation selection, modal focus, empty state, copy-link composition, and mobile layout were visually confirmed.
- `memoryos-web:mem12` builds from the production Dockerfile. `nginx -t` passes the generated image configuration; the expected non-root `user` warning is retained because the runtime intentionally starts as UID 101.

## Static and repository gates

- JetBrains inspections with warnings enabled report no errors. The only remaining weak warnings are IntelliJ's header-name registry rejecting the standard `Referrer-Policy` header and the intentional custom `X-MemoryOS-CSRF` and `X-MemoryOS-Logout-Location` headers; their values are exercised by browser integration tests.
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

## Shared self-registration and captured-email evidence

On 2026-08-25, PR #27 head `70be571` was deployed to the staging server. The deployment runs Mailpit `v1.31.0` from digest `sha256:c96991d9bef73594c246d89ca81411d4e916f03e76a7d2d72fa2ab5dd3c9ce24`, persists at most 500 messages for seven days, accepts only authenticated STARTTLS SMTP on the internal Compose network, and publishes only its web mailbox to server loopback through a dedicated single-service bridge. A deployment-owned private CA signs the `mailpit`/`memoryos-mailpit` SMTP certificate; Keycloak imports that CA through `KC_TRUSTSTORE_PATHS`. Generated CA, key, certificate, and SMTP-auth files remained mode `0600`, and only the certificate fingerprint was printed.

The first Keycloak recreation exposed that imported PEM trust material generates `/opt/keycloak/data/keycloak-truststore.p12`; the read-only container therefore needs its complete data directory on tmpfs rather than only `data/tmp`. The corrected deployment recreated Keycloak healthy, retained the shared PostgreSQL realm state, and preserved the public `memoryos` and `orgmemory` issuers.

Secret-safe realm inspection then proved:

```text
registrationAllowed=true
registrationEmailAsUsername=true
verifyEmail=true
smtpHost=mailpit
smtpPort=1025
smtpAuth=true
smtpStarttls=true
smtpSsl=false
smtpPasswordConfigured=true
```

A real Chromium session opened the public MemoryOS Keycloak registration surface, created one temporary `@memoryos.test` recipient, and reached the `VERIFY_EMAIL` required action. Mailpit received one `Verify email` message for that exact recipient. The browser followed the captured action-token link without printing it, reached `UPDATE_PASSWORD`, set the recipient password, and completed the required action. A subsequent administrator read observed exactly one enabled user with `emailVerified=true`. The temporary user and captured message were deleted; the mailbox returned to zero messages, the generated browser password was cleared, and no invitation, Actor, membership, or application session was created by this credential-only verification.

All five Compose services were healthy afterward, `/actuator/health` returned `UP`, and both public realm discovery documents retained their exact HTTPS issuers. PR #27 latest-head CI run `32805933698` passed `check`, `frontend`, and `frontend-image`; the single CodeRabbit pass produced three provisioning findings, all fixed and resolved.

## Protected mailbox and current pgweb database evidence

On 2026-08-25, PR #27 head `23d7b2f` extended the staging deployment with digest-pinned OAuth2 Proxy `v7.15.3` and the dedicated confidential `memoryos-mailpit` Keycloak client. The proxy uses Authorization Code with S256 PKCE, a one-hour secure minimal cookie, file-mounted client/cookie secrets, and an exact initial-owner email file. All three files remained mode `0600`; the allowlist returned to exactly one line after verification. Mailpit remains absent from the public proxy network: OAuth2 Proxy alone joins the isolated mailbox-access bridge and Nginx Proxy Manager network.

Nginx Proxy Manager now owns `https://memoryos-mail.72-62-193-33.nip.io`, forwarding to `memoryos-mailpit-oauth2-proxy:4180` with WebSockets, exploit blocking, forced HTTPS, HTTP/2, HSTS, and an exact-domain Let's Encrypt certificate expiring 2026-11-23. An unauthenticated request redirected to the `memoryos-mailpit` authorization endpoint with the exact HTTPS callback and `code_challenge_method=S256`.

A real Chromium session registered and verified one temporary non-allowlisted Keycloak user. Its first OAuth2 callback returned `403`, proving that realm authentication alone cannot read captured verification links. The same user was added temporarily to the watched email file, re-authenticated without restarting the proxy, and reached the live `Mailpit - MemoryOS staging` UI through the public HTTPS origin. The owner-only file was restored, the verified Keycloak user and captured email were deleted, the browser password was cleared, and all temporary browser, tunnel, NPM-operator, and provisioning artifacts were removed. OAuth2 Proxy remained stable with zero restarts and Nginx Proxy Manager reached its `/ping` endpoint.

The separately protected pgweb deployment was cut over from legacy `zeromail-postgres`/`shared-postgres` to `memoryos-postgres` on `memoryos-internal`. Its dedicated `memoryos_pgweb` role connects to the `memoryos` database with transaction read-only enabled, can select `actors`, and cannot insert, update, or delete it. The pgweb and OAuth2 Proxy containers remained healthy, and `https://memoryos-db.72-62-193-33.nip.io` continued to redirect through the unchanged `memoryos-pgweb` S256 PKCE client.

## Public staging application evidence

On 2026-08-25, PR #27 head `4c42cb6` published `https://memoryos.72-62-193-33.nip.io` through Nginx Proxy Manager to `memoryos-web:8080`. The host enforces HTTPS, HTTP/2, HSTS, WebSockets, and exploit blocking with exact-domain Let's Encrypt certificate #28 expiring 2026-11-23. `/` served the production MemoryOS application and `/actuator/health` returned `UP`.

The `memoryos-web` client now retains only `https://memoryos.72-62-193-33.nip.io/login/oauth2/code/memoryos`, uses the matching HTTPS root/web origin, and keeps mandatory S256 PKCE. A real Chromium click on `Continue with company account` reached the public `memoryos` authorization endpoint with `client_id=memoryos-web`, the exact HTTPS callback, and `code_challenge_method=S256`. The temporary Nginx Proxy Manager operator was removed after application-level provisioning.

This closes the missing shared self-registration and email-verification prerequisite. It does not prove delivery to public mail providers: Mailpit is intentionally staging-only and captures rather than relays. One final combined runtime pass—owner invitation intake through this freshly self-registered recipient and atomic acceptance—plus guarded merge/exact-SHA closure remains before MEM-12 can move to completed.
