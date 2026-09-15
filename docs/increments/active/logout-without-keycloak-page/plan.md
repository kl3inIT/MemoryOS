# Plan

- [x] Confirm on local Keycloak 26.7.0 that the ID token carries `sid` and the admin API deletes exactly that session.
- [x] Record `sid` at login, end the Keycloak session in a logout handler before session invalidation, and return the provider logout location only as fallback.
- [x] Navigate to `/` after a provider-ended sign-out in the account menu.
- [x] Update the identity spec, verification matrix, roadmap and AGENTS.md.
- [x] Run the core, API and web gates for the changed surfaces.
- [ ] Owner acceptance on staging; commit, PR and Linear remain owner decisions.

## Verification — 2026-09-15

- Local Keycloak 26.7.0 probe with a confidential client and a `manage-users` service account: the password-grant ID token contains `sid`, which is one of the user's sessions; `DELETE /admin/realms/memoryos/sessions/{sid}` returns `204`, a repeat returns `404`, the session is no longer listed, and the deleted session's refresh token is rejected with `400 invalid_grant`.
- `./gradlew :core:test --tests io.memoryos.iam.keycloak.KeycloakProviderSessionTerminatorTest --tests io.memoryos.ModulithArchitectureTest --tests io.memoryos.CoreDependencyRulesTest :api:test --tests io.memoryos.api.security.SessionSecurityIntegrationTest --tests io.memoryos.api.security.ActorSessionLoginSuccessHandlerTest`: build successful; 5, 1, 2, 15 and 3 tests passed. The integration tests prove the logout handler receives the recorded `sid` before session invalidation, that an ended provider session yields `204` without a location, and that a provider that cannot end the session yields the provider logout location with the local session still invalidated.
- Web: `oxfmt --check` on the changed files, `oxlint --deny-warnings`, `tsc -b` and `search-page.test.tsx` (17 tests) pass. `identity-shell.spec.ts` passes 16 of 16 serially, including sign-out without a location reaching Keycloak sign-in and the provider-page fallback. The global pnpm package was missing locally, so the checks ran through `node_modules/.bin` and the e2e server through a scratch Playwright config with the same settings.

Not verified: staging. Sessions created before deployment have no recorded `sid`, so their first sign-out still uses the provider logout page.
