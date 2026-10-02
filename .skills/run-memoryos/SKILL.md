---
name: run-memoryos
description: Run, start and drive the MemoryOS web app locally, take screenshots of a page, click through a UI flow, or smoke-test a frontend change against the fixture backend; also run a targeted core Gradle test. Use when asked to run MemoryOS, open or screenshot a page, check a UI change in the real app, or run one backend test.
---

# Run MemoryOS

Start the browser app on its fixture backend (`pnpm --dir web dev:e2e`, no secrets). Then drive it with
`.skills/run-memoryos/driver.mjs`: the script fakes the signed-in identity, runs one command per line and saves
screenshots to `output/run-memoryos/`. Backend changes are checked with a targeted Gradle test. Every path is
relative to the repository root. Commands are PowerShell on Windows, which is where they were verified.

## Prerequisites

- JDK 25 at `JAVA_HOME`, plus the Node and pnpm versions in the [README](README.md#requirements).
- Web dependencies and Playwright's Chromium:

```powershell
pnpm --dir web install --frozen-lockfile
pnpm --dir web exec playwright install chromium
```

## Run (agent path): web UI

1. Start the server **in the background** (Claude Code: `run_in_background`). It regenerates the API client, then
   serves Vite on `127.0.0.1:4173` with an in-process fixture backend. It is ready within about 10 s:

```powershell
pnpm --dir web dev:e2e
```

```powershell
curl.exe -s -o NUL -w "%{http_code}" http://127.0.0.1:4173/   # 200 when ready
```

2. Drive it. Commands go as arguments or on stdin, one per line:

```powershell
@'
goto /
wait text=GPT-5 mini
fill role=textbox[name="Câu hỏi"] :: Chính sách nghỉ phép năm nay thế nào?
press Enter
wait text=System.out.println
wait role=button[name="Gửi câu hỏi"]
shot chat-answer
'@ | node .skills/run-memoryos/driver.mjs
```

```powershell
node .skills/run-memoryos/driver.mjs --mobile --lang en "goto /" "shot chat-home-en" "aria main"
```

Options: `--identity owner|member|signed-out|<file.json>` (default `owner` with every capability), `--lang vi|en`
(default `vi`), `--mobile` (390x844 instead of 1440x900), `--route "<path|glob>=<file.json>"` (repeatable),
`--headed`. Commands: `goto <path>`, `click <selector>`, `fill <selector> :: <text>`, `press <key>`,
`wait <selector|ms>`, `text <selector>`, `aria [selector]`, `eval <js>`, `shot <name> [full]`,
`viewport <w> <h>`. Selectors are Playwright selectors (`role=button[name="…"]`, `text=…`, CSS). Use `aria main` to
find them.

The driver prints `url …` after each `goto`, plus `[api 404] GET /api/…` for every failed API call and any page
errors. If a command fails, it saves `output/run-memoryos/failure.png` and exits 1. **Open every PNG with Read
before you call the change done.**

3. Pages outside chat need stubs. The fixture backend answers only `/api/chat/*`, so each `[api 404]` line names an
   endpoint to stub. Write the response, typed by `web/src/lib/hey-api/types.gen.ts`, to a file under the ignored
   `output/` directory. A `/api/...` route key matches that exact path with any query string:

```powershell
New-Item -ItemType Directory -Force output/run-memoryos/stubs | Out-Null
@'
{"items":[{"id":"0b9e7d42-5c1f-4e8a-a6d3-7f2c1b0e4a55","name":"Phòng Kế toán","systemKey":null,"memberCount":14,"managerCount":2,
 "capabilities":["SEARCH_READ","CHAT_WRITE"],
 "permissions":{"manage":true,"manageMembers":true,"delete":true,"editPermissions":true,"manageSources":true}}],
 "page":0,"size":20,"totalItems":1,"totalPages":1}
'@ | Set-Content -Encoding utf8 output/run-memoryos/stubs/groups.json
node .skills/run-memoryos/driver.mjs --route "/api/groups=output/run-memoryos/stubs/groups.json" "goto /admin/groups" "wait text=Phòng Kế toán" "shot admin-groups"
```

4. Stop the server when you are done. If it keeps running, it blocks `pnpm test:e2e`. The background task then
   reports exit code 255, which is expected:

```powershell
Get-NetTCPConnection -LocalPort 4173 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
```

## Run: backend test

Most changes land in `core`. Run the owning test class narrowly. A plain unit test needs no Docker and takes about
20 s once `core` is compiled:

```powershell
.\gradlew.bat :core:test --tests '*IdentityValueObjectsTest' --no-daemon
```

The full API with worker, Keycloak and MinIO was not run for this skill. It needs `infisical login` (only the user
can run it) and the development Keycloak and MinIO; follow
[the development runtime runbook](docs/runbooks/development-runtime.md) instead.

## Gotchas

- **Identity is faked in the browser, not the backend.** The driver answers `/api/identity/me` through Playwright
  routing, so `curl http://127.0.0.1:4173/api/identity/me` still returns 404.
- **`--identity signed-out` shows no sign-in page.** The app sends a signed-out visitor straight to
  `/oauth2/authorization/memoryos`. The fixture's OAuth stub then ends on `/login/oauth2/code/memoryos`, a blank page
  that reads `SESSION=oauth-state`. That page is the expected result.
- **The chat answer is canned.** Every question gets `Hello 👋 … System.out.println("Hello"); … Reference`. While it
  streams, the composer shows Stop. Wait for `role=button[name="Gửi câu hỏi"]` before you capture the finished state.
- **The model picker loads after `goto`.** A screenshot taken right away shows `Đang tải mô hình…`. Wait for
  `text=GPT-5 mini` first.
- **Normal noise:** `[api 404]` for `/api/chat/voice`, `/api/chat/preferences`, `/api/chat/settings`,
  `/api/chat/web`, `/api/chat/images`, `/api/chat/library` and `/api/mcp/connections`. The chat shell renders
  without them.
- **Inline JSON in PowerShell loses its quotes.** `--route '/api/x={"a":1}'` reaches Node as `{a:1}`. The driver
  rejects it, so pass a file.
- **`dev:e2e` runs `generate:api` first.** If `openapi.yml` changed, files under `web/src/lib/hey-api/` change too.
- **`java` on `PATH` is the Oracle `javapath` shim.** It crashes: a segfault in Git Bash and exit 5 in PowerShell.
  `gradlew.bat` uses `JAVA_HOME` (Temurin 25) and works. Do not debug with a bare `java -version`.
- **RAM is tight.** The `hs_err_pid*.log` files in the repository root are earlier JVM crashes. Prefer one targeted
  test over `clean check`, and leave the full gate to CI when memory is short.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| `Error: http://127.0.0.1:4173 is already used … set reuseExistingServer:true` from `pnpm test:e2e` | Your `dev:e2e` server is still running. Stop it with the `Get-NetTCPConnection` line above. |
| `--route body is not valid JSON: {items:[…` | PowerShell stripped the quotes from inline JSON. Write the stub to a file. |
| A page shows its "unavailable" or error state and `[api 404] GET /api/<x>` is printed | Stub `/api/<x>` with `--route`. |
| A stubbed page still shows an error state and no 404 is printed | The stub body is malformed or has the wrong shape. Check it against `types.gen.ts`. |
| `locator.waitFor: Timeout 30000ms exceeded` | The text or selector never appeared. Open `output/run-memoryos/failure.png` and run `aria main` to see the real labels; Vietnamese is the default language. |
