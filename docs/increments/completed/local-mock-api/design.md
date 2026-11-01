# Local mock API

## Accepted scope

Let a developer browse the web app by hand against the synthetic chat fixture used by the browser tests, and fix a fallback-title translation lookup found while doing so. No production code path, API contract or deployment changes.

- `pnpm mock-api` (`web/scripts/start-mock-api.ts`) serves `handleChatFixture` and the OAuth redirect stubs on `127.0.0.1:8081` (`MEMORYOS_MOCK_API_PORT`). The backend is shared with `start-e2e-server.ts` through `web/scripts/fixture-backend.ts`, so the two cannot drift.
- `MEMORYOS_E2E_PORT` overrides the frontend port of `pnpm dev:e2e` (default 4173).
- The fixture answers `GET /api/identity/me` with a synthetic Tenant member and `GET /api/chat/sessions/search` (title substring, permanent sessions, `{ items: [{ session, snippet: null }], hasMore }`) so the shell and the history search work without a browser-side route. The browser specs keep routing their own identity and search, which wins over the fixture.
- `initialChatTitle` looks the fallback title up with `{ ns: "app", nsSeparator: false, keySeparator: false }`, so the `:` of a key never splits it into a namespace.

## Decisions

- Projects and Library files are seeded for `pnpm mock-api` only: `start-mock-api.ts` calls `seedChatProjects(mockProjects)` and passes `createFixtureBackend({ library: true })`, which serves `handleMockLibrary` (`tests/fixtures/mock-workspace.ts`, shaped from the generated `/api/chat/library*` types) ahead of the chat fixture. `start-e2e-server.ts` does neither, so browser specs that count Projects or files are unaffected.
- No `.env` example: the Vite configuration reads `MEMORYOS_API_URL` from the process environment, not from `.env` files.

## Verification

`chat-transport.test.ts` for the title; start `pnpm mock-api` on another port and read `/api/identity/me` and `/api/chat/sessions/search`; web typecheck, lint and format.
