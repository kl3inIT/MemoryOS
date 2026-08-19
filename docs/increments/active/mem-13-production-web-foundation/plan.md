# MEM-13 implementation plan

## Slice 1: Contract and application foundation

- [x] Add root `openapi.yml` for the existing identity endpoint.
- [x] Scaffold root `web/` from the pinned official Vite React TypeScript template.
- [x] Pin pnpm and frontend dependencies; commit the exact lockfile.
- [x] Configure Hey API Fetch, SDK, TypeScript, and TanStack Query generation.
- [x] Add deterministic generated-client drift verification.
- [x] Configure TanStack Router file routes, TanStack Query, Tailwind, Oxlint, and test tooling.

## Slice 2: Authenticated owner shell

- [x] Isolate `GET /api/identity/me` as the only API endpoint accepting either bound bearer authentication or an existing browser session.
- [x] Prove bearer requests remain request-scoped and other API routes remain stateless.
- [x] Implement session checking, authenticated, unauthenticated, unavailable, and retry behavior.
- [x] Replace the initial signed-out marketing composition with a direct, responsive authentication gate.
- [x] Implement the public `/access-not-provisioned` route.
- [x] Build the responsive monochrome enterprise owner shell without fake product data or dead controls.
- [x] Add focused route and auth-state behavior tests.

## Slice 3: Same-origin production runtime

- [x] Build immutable web assets in a pinned Node image and serve them from unprivileged Nginx.
- [x] Configure SPA fallback while excluding backend-owned paths.
- [x] Add the hardened web service to production Compose.
- [x] Document external reverse-proxy routing and local Vite proxy behavior.
- [x] Add Node/pnpm setup and independent frontend gates to CI.

## Slice 4: Runtime verification

- [x] Run Hey API freshness, lint, typecheck, unit test, and production build gates.
- [x] Run focused backend bearer and browser-session identity tests.
- [x] Exercise unauthenticated login initiation through the real web/API boundary.
- [x] Exercise provisioned-owner login and authenticated shell rendering through Playwright.
- [x] Refresh and directly navigate while retaining the JDBC-backed session.
- [x] Exercise the public not-provisioned state and network-error recovery.
- [x] Build and smoke-test the production web container with a read-only filesystem.
- [x] Inspect every changed IDE-supported file and run repository `clean check`.

## Slice 5: Durable records

- [x] Update architecture, identity contract, verification matrix, runtime runbook, and roadmap.
- [x] Record commands and observed evidence in `verification.md`.
- [x] Keep this increment under `active/` until its pull request merges.