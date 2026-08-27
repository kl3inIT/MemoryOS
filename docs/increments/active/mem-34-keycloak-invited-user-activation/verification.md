# MEM-34 verification

## Implemented surface

- Identity owns a concrete Keycloak Admin Client provisioner behind one narrow root-package invitation API.
- Invitation issue creates the pending digest-only row, provisions or reuses the exact Keycloak recipient synchronously, and commits only after provider success.
- New recipients receive bounded `VERIFY_EMAIL` and `UPDATE_PASSWORD` actions; MemoryOS-created unverified users are reused on retry; exact verified users are reused without password reset; unrelated unverified or ambiguous accounts fail closed.
- Provider calls use explicit connect, connection-request, and read timeouts. Provider conflict and provider unavailability use safe typed business failures, with temporary provider failures rendered as `503` Problem Details.
- The fixed public `/invite/activate` route carries no invitation correlation, clears stale continuation state, marks only the activation UX source, applies no-store/no-referrer, and starts the existing browser OAuth2 flow.
- A callback with no capability-link continuation accepts only the exact configured issuer, nonblank subject, provider-verified email, and exactly one pending unexpired normalized-email invitation before entering the existing atomic binding/member/accept transaction.
- Issued Invitation responses project `ACTIVATION_EMAIL_SENT`, `EXISTING_ACCOUNT`, or `RECOVERY_LINK_ONLY`; the owner UI presents activation, existing-account, and rotated-recovery outcomes without exposing provider internals.
- Keycloak desired state disables public self-registration, registers the exact callback plus `/invite/activate`, and creates realm-local service-account client `memoryos-user-provisioner` with direct `manage-users` only.

## Current evidence

- `:core:compileJava :api:compileJava` passes.
- `:core:compileTestJava :api:compileTestJava` passes.
- `DefaultInvitationServiceTest` passes invitation provisioning invocation, rollback on provider failure, direct verified-email acceptance, no-match/unverified denial, and recovery-link-only rotation.
- `KeycloakInvitationRecipientProvisionerTest` passes create-and-activate, exact MemoryOS-origin retry, existing verified reuse without reset, unrelated/ambiguous conflict, and bounded provider timeout against a local standards-shaped Admin REST server.
- `SessionSecurityIntegrationTest` passes both capability-link acceptance and `/invite/activate` verified-email acceptance through real Authorization Code + S256 PKCE with JDBC Spring Session and final provider/invitation-state absence.
- `OpenApiContractTest` passes against the regenerated committed browser contract; the Hey API client is regenerated and its drift check passes.
- JetBrains warnings-enabled inspection reports no errors or unresolved warnings. IntelliJ retains only the pre-existing weak header-name warnings for standard `Referrer-Policy` and intentional `X-MemoryOS-CSRF`; both values are exercised by browser integration tests.
- IntelliJ project build and checked-in Gradle compile pass.
- Focused `ModulithArchitectureTest`, `CoreDependencyRulesTest`, capability tests, API HTTP/session tests, and `OpenApiContractTest` pass.
- `gradlew.bat clean check --no-daemon` passes the repository-wide backend gate.
- `pnpm check` passes generated-client drift, lint, formatting, TypeScript, 25 unit tests, route drift, font-asset policy, and production build.
- `pnpm test:e2e` passes 14/14 Chromium browser contracts, including activation-delivery and recovery-link presentation.
- `sh -n infrastructure/keycloak/configure-memoryos-realm.sh` and JSON parsing of `memoryos-user-provisioner-client.json` pass.

## Remaining delivery evidence

- Secret-safe realm reconciliation proving self-registration disabled, exact redirect set, provisioner role/secret state, and no broader direct realm-management role.
- Exact reviewed staging SHA through owner invite, captured Keycloak action email, recipient email verification/password setup, `/invite/activate`, automatic acceptance, Organization `MEMBER`, and `ActorId`-only final session.
