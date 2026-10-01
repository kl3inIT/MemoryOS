# Plan

- [x] Create and update generic OIDC providers with `openid email profile`.
- [x] Extend identity-provider administration tests to protect the standard scope and existing profile-security settings.
- [x] Update the identity contract and verification matrix with upstream standard-claim behavior.
- [x] Record a display name from `given_name` and `family_name` when Keycloak does not emit `name`.
- [x] Publish the recorded display name through the current-identity projection and account menu.
- [x] Cover claim fallback and account-menu presentation.
- [x] Align the current-identity OpenAPI contract and regenerate the Hey API client.

## Verification — 2026-09-28

- `./gradlew.bat :core:compileTestJava --no-daemon`: passed.
- `./gradlew.bat :core:test --tests "*DefaultIdentityProviderAdministrationTest" --no-daemon`: passed — 9 tests.

## Verification — 2026-10-01

- `./gradlew.bat :api:test --tests "*ActorSessionLoginSuccessHandlerTest" --rerun-tasks --no-daemon`: passed — 5 tests, including the Keycloak first/last-name fallback.
- `corepack pnpm --config.verify-deps-before-run=false --dir web exec vitest run src/components/app-shell/app-shell.test.tsx`: passed — 4 tests.
- Web `tsc -b`, changed-file oxlint, and changed-file oxfmt checks passed.
- The local `OpenApiContractTest` remains unavailable because Testcontainers cannot discover Docker Desktop, although `docker version` succeeds. The current-identity contract and Hey API client are aligned; CI validates the committed contract.
