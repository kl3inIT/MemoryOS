# Browser-test backend scripts

Both scripts run the synthetic chat fixture (`tests/fixtures/chat-server.ts`) as the backend. All data is in memory and is lost when the process stops. The fixture is test tooling: it makes no claim about model, IAM or retention behavior.

## `pnpm dev:e2e`

Starts the fixture backend on a free port and the Vite dev server (or, with `MEMORYOS_E2E_PREVIEW=1`, the production build) on `127.0.0.1:4173`. Playwright runs against it. Override the frontend port with `MEMORYOS_E2E_PORT`.

## `pnpm mock-api`

Starts only the fixture backend on `127.0.0.1:8081` so the app can be browsed by hand. Override the port with `MEMORYOS_MOCK_API_PORT`. Besides the chat routes it answers `GET /api/identity/me` with a synthetic Tenant member and `GET /api/chat/sessions/search`; every other route is the fixture's own, and an unknown one is a 404.

```sh
# Terminal 1
pnpm mock-api

# Terminal 2: the Vite proxy reads MEMORYOS_API_URL when it starts
MEMORYOS_API_URL=http://127.0.0.1:8081 pnpm dev        # bash
$env:MEMORYOS_API_URL = "http://127.0.0.1:8081"; pnpm dev   # PowerShell
```

Create a conversation to browse:

```sh
curl -X POST http://127.0.0.1:8081/api/chat/test-fixture \
  -H "Content-Type: application/json" -d '{"title": "Test conversation"}'
```

`pnpm mock-api` alone also seeds three Projects (two with conversations, one empty) and serves the Library routes of `tests/fixtures/mock-workspace.ts`: nine sample files (documents, images, audio, generated, one in the trash) with varied dates, plus a shared meeting, a Source document and the usage and trash window. Stars persist in memory; other writes are acknowledged and ignored. `pnpm dev:e2e` seeds and serves none of this, so the browser specs keep their own counts and routes. Pages that need other routes the fixture does not serve (Meetings, administration) show their error state; add the route only when a browser spec needs it.
