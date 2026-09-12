# MEM-59 — JIT implementation verification

## Scope

Implemented on branch `feat/mem-59-tasco-jit`. Browser admission uses the strict signed ID-token provider claim and deployment allowlist; exact Actor binding, MEMBER/Basic authority, denial of inactive memberships, existing invitations, and token-free application sessions are preserved. No schema migration or frontend contract change was needed.

## Executed checks

JDK: Eclipse Temurin 25.0.3, checked-in Gradle 9.7.0 wrapper, Windows with Docker.

- `gradlew.bat :core:test --tests "*DefaultTrustedIdentityAdmissionTest" :api:test --tests "*ActorSessionLoginSuccessHandlerTest" --tests "*SessionSecurityIntegrationTest" --no-daemon --max-workers=2` — passed in 1m35s.
- `gradlew.bat clean check --no-daemon --max-workers=2` — passed in 8m02s. JUnit reports: API 48, core 138, connector 20, worker 22; 228 total, 225 passed, zero failures/errors, three opt-in Docling live-provider tests skipped.
- Git Bash `bash -n infrastructure/keycloak/configure-memoryos-realm.sh` — passed. The Windows `bash` launcher first failed because its WSL installation lacked `/bin/bash`; using installed Git Bash resolved the tooling issue.

## Runtime boundary exercised

`SessionSecurityIntegrationTest` starts the actual Spring Boot HTTP server on a random port, uses a signed synthetic OIDC provider over HTTP, cookie-aware clients, and isolated PostgreSQL through Spring Session JDBC. Its trusted JIT scenario exercises authorization redirects, code exchange, callback, identity projection, a pending invitation remaining unchanged, repeat login with the same Actor, rejection after local deactivation, and absence of provider token markers in persisted sessions. The rejection scenario covers absent/untrusted/non-String provider claims and unbound bearer tokens carrying the same provider claim without creating application authority.

`DefaultTrustedIdentityAdmissionTest` verifies exact binding reuse, separate issuer namespaces, MEMBER/non-manager Basic-only creation, preservation of existing authority, inactive/missing Tenant and inactive membership denial, rollback after Basic provisioning, and concurrent first admission returning the same committed Actor. `ActorSessionLoginSuccessHandlerTest` verifies that UserInfo cannot supply a missing ID-token provider claim, another issuer cannot select JIT, and an empty allowlist grants no JIT.

## Environment and remaining acceptance

- The local ignored `.memoryos-dev.yaml` contains `MEMORYOS_JIT_ALLOWED_PROVIDER_ALIASES: tasco`. Only non-secret policy metadata was inspected; this file was not modified.
- The user supplied debugger evidence that their Keycloak MemoryOS ID token contains String `memoryos_identity_provider=tasco` before this implementation. This is prerequisite evidence, not a post-change live Tasco acceptance run.
- The user subsequently reported successful Tasco-simulator JIT login. A read-only query against the running local PostgreSQL confirmed that the brokered subject had MEMBER/ACTIVE membership in an ACTIVE Tenant. This is user-exercised two-Keycloak simulator evidence plus database confirmation, not an agent-driven full browser run or actual corporate Tasco acceptance.
- The new mapper reuses the existing mapper upsert and was checked for correct client placement and shell syntax; live create/replay/drift correction against Keycloak was not executed. No remote realm/client/user configuration was changed.
- Java LSP diagnostics were attempted and reported no language server available. No JetBrains-inspection-clean claim is made.
- Frontend was intentionally unchanged; no frontend build or visual UI verification is claimed.
- Upstream revocation propagation and absolute browser-session lifetime remain outside this increment. An existing admitted member is not deactivated merely by removing JIT opt-in.

## Cleanup and next local verification

No temporary application scripts, remote users, invitations, or manual database records were created. Integration fixtures use isolated databases and manage their container lifecycle.

Login through the configured two-Keycloak simulator has succeeded as reported above. The subsequent user-initiated broker logout reached Tasco and failed with `Invalid redirect uri`. Independent unauthenticated probes confirmed that Tasco accepted the login authorization request (HTTP 200 with login form, exact `memoryos-broker` client, S256), but rejected the broker post-logout return `https://auth.kl3in.tech/realms/memoryos/broker/tasco/endpoint/logout_response` (HTTP 400). Keycloak MemoryOS accepted its local application post-logout return. The operator was instructed to add the exact broker return to Tasco client's **Valid post logout redirect URIs**, without replacing the login callback or using a wildcard. Successful post-fix logout has not yet been reported or verified. No remote configuration was changed by the agent, and no logout URL containing an ID-token hint was reused or recorded.

Remaining live acceptance: confirm logout returns through the broker to the originating local app, exercise repeat login and normal Users deactivation followed by denied Tasco login, and record actual corporate Tasco acceptance separately. Local and staging application databases/memberships remain independent; landing at staging `/access-not-provisioned` does not invalidate confirmed local membership.
