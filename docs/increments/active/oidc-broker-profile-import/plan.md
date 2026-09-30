# Plan

- [x] Create and update generic OIDC providers with `openid email profile`.
- [x] Extend identity-provider administration tests to protect the standard scope and existing profile-security settings.
- [x] Update the identity contract and verification matrix with upstream standard-claim behavior.

## Verification — 2026-09-28

- `./gradlew.bat :core:compileTestJava --no-daemon`: passed.
- `./gradlew.bat :core:test --tests "*DefaultIdentityProviderAdministrationTest" --no-daemon`: passed — 9 tests.
