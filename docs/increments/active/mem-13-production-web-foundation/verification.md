# MEM-13 verification

Date: 2026-08-19

## Repository gates

- `./gradlew.bat clean check --no-daemon` — succeeded in 15 seconds on the final worktree; 17 actionable tasks, 7 executed, 9 from cache, and 1 up-to-date.
- `cd web && pnpm check` passed in 29.55 seconds on the final worktree: generated-client freshness, zero-warning Oxlint, formatting, TypeScript, three Vitest files with four tests, route-tree freshness, and the Vite production build all passed.
- A deliberate edit to generated `src/lib/hey-api/index.ts` made `pnpm check:api` fail with the changed path; regeneration restored it and the next freshness check passed.
- `cd web && pnpm test:e2e` passed all five Playwright scenarios in 13.7 seconds, including a real Vite proxy regression that returns a production-shaped `Secure` session cookie and proves the local HTTP callback receives that session.
- `pnpm outdated` returned no entries after installation of the pinned package set.
- IntelliJ inspections with warnings enabled reported no problems in every changed TypeScript, TSX, CSS, and package-manifest file.

## Browser evidence

The real Vite application was exercised in Chromium at `1440 × 900` and `390 × 844`:

- a `401` identity response rendered the direct `Sign in to MemoryOS` gate on a white canvas and exposed the exact `/oauth2/authorization/memoryos` action as `Continue with company account`;
- a session-shaped `200` identity response rendered a compact owner workspace with one near-black identity panel, one white session panel, and the stable actor ID without fake account or workspace data;
- the monochrome Geist-based layout remained readable at desktop and mobile widths with no horizontal overflow or inaccessible primary action;
- direct navigation rendered `/access-not-provisioned` with the same monochrome system and independently from signed-out state;
- a transient `503` rendered the unavailable state and the retry action recovered to the authenticated shell.
- With Vite on `http://localhost:8080` and an SSH loopback forward to the healthy deployed API, the signed-out shell rendered from a real `401`. Its login action reached the live Keycloak page titled `Sign in to MemoryOS` with the exact `http://localhost:8080/login/oauth2/code/memoryos` callback and `code_challenge_method=S256`.
- Before the fix, Chromium stored the deployed API's `SESSION` cookie as `Secure` but omitted it from local HTTP identity and callback requests. After the Vite response rewrite, the cookie was stored without `Secure` only on the loopback development origin and the next `/api/identity/me` request included `SESSION`; the live authorization request still reached Keycloak with S256 PKCE.
- The first live callback created an authenticated JDBC session, but the deployed `memoryos-api:sha-54893747a459e7ce082ce4fd1348967b590bb707` image predated the dedicated session-aware `/api/identity/me` security chain. Replaying that authenticated session against the stale image returned `200` from the browser-only `/` endpoint and `401` from `/api/identity/me`, reproducing the remaining sign-in gate.
- After deploying the current API composition, the same persisted browser session returned `200` from `/api/identity/me` with actor ID `2c04758d-6715-4ad2-ad83-1212c9716e8e`. Chromium rendered the authenticated owner workspace at `1440 × 900` with `Status: Active`, `Role: Owner`, `Transport: Same origin`, and no horizontal overflow.
- the changed identity, owner-shell, shared route-state, stylesheet, manifest, and focused browser-contract files received clean IntelliJ inspections with warnings enabled.

`BrowserAuthenticationIntegrationTest` exercised the real Spring API composition, Authorization Code + S256 PKCE callback, JDBC-backed session, provider-principal replacement, and session-authenticated `GET /api/identity/me`. `JwtAuthenticationIntegrationTest` retained bearer-only identity behavior. Both are included in the successful repository gate.

## Production runtime evidence

- `docker compose -f infrastructure/deployment/compose.production.yaml config --quiet` succeeded with representative non-secret deployment variables and both image references.
- `docker build --file web/Dockerfile --tag memoryos-web:local .` succeeded from the pinned Node and Nginx digests and produced immutable assets.
- The built image started as unprivileged Nginx under the Compose-equivalent read-only filesystem and temporary-volume constraints.
- `GET /` returned `200`, `Cache-Control: no-cache`, the content security policy, clickjacking protection, MIME-sniff protection, and the built SPA.
- A hashed asset returned `Cache-Control: public, max-age=31536000, immutable`.
- `/api/identity/me` and `/actuator/health` reached an API smoke process through Nginx rather than SPA fallback. The upstream observed `X-Forwarded-Host: memory.example` and `X-Forwarded-Proto: https`.
- The live verification API was rebuilt from the current worktree and deployed as `memoryos-api:worktree-56d7f250c3a4-20260819`; Compose recreated it healthy. This tag is verification evidence, not a release tag: the final release still requires a reviewed commit and matching immutable SHA tag.
- `/access-not-provisioned` used SPA fallback and returned the application shell.
