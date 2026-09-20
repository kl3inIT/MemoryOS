# MEM-65 implementation plan: MemoryOS Keycloak authentication theme

## Design and source alignment

- [x] Confirm the approved MemoryOS auth composition and removed visual elements.
- [x] Inspect the pinned Keycloak 26.7 runtime contract and exact upstream `keycloak.v2` theme/template sources.
- [x] Choose resource-only inheritance so Keycloak retains ownership of all forms and action behavior.

## Theme implementation

- [x] Add the `memoryos/login` theme with `keycloak.v2` inheritance.
- [x] Add local MemoryOS logo, favicon, meaning-network artwork, and responsive CSS.
- [x] Add English message overrides for sign-in and required-action copy without changing provider semantics.
- [x] Cover sign-in, reset credentials, update password, verify email, info, and error states through inherited templates.

## Runtime integration

- [x] Mount the theme read-only into the shared Keycloak service.
- [x] Reconcile and verify `loginTheme=memoryos` only for the `memoryos` realm.
- [x] Document the restart/reconciliation and rollback procedure.

## Verification

- [x] Add automated theme structure and runtime-wiring tests to CI.
- [x] Validate shell, YAML/Compose, CSS, SVG, and message resources.
- [x] Browser-check representative Keycloak markup at desktop and mobile sizes, including focus and overflow.
- [x] Exercise sign-in, recovery, password update, email verification, and terminal errors against Keycloak 26.7.0.
- [x] Run the repository and frontend gates, recording the unavailable-Docker limitation for integration tests.
- [x] Record verification evidence and consolidate durable architecture, identity, invitation, runbook, and roadmap facts.
