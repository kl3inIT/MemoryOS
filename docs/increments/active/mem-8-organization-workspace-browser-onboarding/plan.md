# MEM-8 implementation plan

## Slice 1: Organization authority

- [x] Add Organization, Workspace, scoped-membership, bootstrap-state, and JDBC-session migration.
- [x] Implement exact Organization aggregate identifiers and active-membership resolver.
- [x] Implement deployment-configured transactional initial-owner bootstrap.
- [x] Serialize concurrent startup with a migration-created singleton row lock.
- [x] Verify exact replay and fail closed on configuration or aggregate drift.
- [x] Cover rollback of identity writes when aggregate creation fails.

## Slice 2: Browser identity

- [x] Add confidential `memoryos-web` Keycloak desired state.
- [x] Create or reuse the named local initial owner with a temporary password and report its stable subject.
- [x] Require Authorization Code and S256 PKCE.
- [x] Add confidential Spring OAuth2 client configuration and fail-fast initial Organization properties.
- [x] Add JDBC-backed Spring Session and secure cookie defaults.
- [x] Resolve exact callback identity and gate admission on active Organization authority.
- [x] Replace the provider principal with `ActorId` and explicitly persist the security context.
- [x] Rotate session ID and discard provider authorized-client state.
- [x] Expose authenticated root and explicit `ACCESS_NOT_PROVISIONED` failure state.

## Slice 3: Scope cutover

- [x] Remove speculative invitation, membership-administration, and context-switch runtime code.
- [x] Remove write-only audit capability and schema.
- [x] Record the evidence-driven audit boundary in ADR 0003.
- [x] Narrow MEM-8 in Linear and link invitation onboarding as MEM-12.
- [x] Reconcile architecture, capability specs, test matrices, roadmap, and runtime runbook.

## Slice 4: Bounded production runtime

- [x] Add a layered, immutable, non-root API container image.
- [x] Add a read-only, health-checked, resource-bounded Compose service on existing shared networks.
- [x] Honor trusted reverse-proxy origin headers for OAuth2 callback generation.
- [x] Build and deploy the exact feature-head image on `zm`.
- [x] Exercise shared PostgreSQL bootstrap, replay, and browser session through the deployed container.

## Verification gates

- [x] Focused bootstrap persistence and concurrency test.
- [x] Real API browser Authorization Code + PKCE integration test.
- [x] Spring transaction-proxy concurrency coverage on H2 and a pinned PostgreSQL 17 Testcontainer.
- [x] Exact deployment-supplied Keycloak browser callback allowlist with wildcard rejection.
- [x] IDE inspection of every changed Java, Kotlin DSL, YAML, properties, and XML file.
- [x] Container image build and Compose configuration validation.
- [x] Shared Keycloak/PostgreSQL initial-owner browser flow.
- [x] `clean check` on the checked-in Gradle wrapper.
- [x] Pull request latest-head CI, review remediation, guarded merge, and exact merge-SHA verification.

Keep this increment under `active/` until the remaining live denial gate is observed. Then move it to `completed/`, reconcile `docs/roadmap.md`, and record final delivery evidence.