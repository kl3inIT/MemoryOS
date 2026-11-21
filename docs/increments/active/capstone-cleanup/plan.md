# Capstone cleanup plan

Each step is one pull request to `main`, run as one Hypomnema Task on `memoryos`. The steps run one after another
because each changes `settings.gradle.kts` or adds a Flyway migration. Every pull request is reviewed before the
merge; none turns on auto-merge.

## 0. Archive (owner's session)

- [x] Tag `archive/pre-capstone-cleanup` on `main` (6829bd3e) and push the tag.

## 1. Remove the chat bots

Done 2026-11-22 in one pull request ([ADR 0032](../../../decisions/0032-no-chat-bots-in-the-capstone-scope.md)).

- [x] Delete `memoryos-channels`, core `io.memoryos.channel`, `memoryos-integration-zalo`,
      `memoryos-integration-whatsapp` (with the bridge) and the Lark bot classes; keep the Lark connector.
- [x] Remove channel use from `chat`, the Worker, `api` and `web` (agents channels panel, chat settings, audit and
      Group copy, `admin.channels` routes). It also took out what only the bots reached: the public-bot reading mode
      (whole Sources, through `connector` and `retrieval`), the MCP `publicBots` switch, IAM's `ServiceAccounts` and
      `MemberLookup`, and `ChatSessionService.leaf`.
- [x] Migration V202 dropping the channel tables and the `chat` channel columns ([data effect](design.md#data-effect-of-v202)).
- [x] Deployment: `channels` services, secrets, images and CI jobs; `deploy.sh` still restores the process on a
      rollback to a staging runtime from before, and removes the one such a runtime left.
- [x] Documents: `ARCHITECTURE.md`, specs, test matrices, runbooks, roadmap, increments `mem-246-lark-bot` and
      `mem-283-whatsapp-bot` (moved to `superseded/`), the Lark delivery of `mem-265-chat-schedules`; ADR 0032
      superseding ADR 0028 and 0031.
- [x] Regenerate `openapi.yml` and the web client; `clean check` and `pnpm --dir web check` in CI.

## 2. Code execution back to the interpreter

Done 2026-11-22 ([ADR 0033](../../../decisions/0033-code-execution-through-memoryos-interpreter-again.md)), in one pull
request with step 3.

- [x] Restore the interpreter client and its wiring removed by MEM-271 (`16e0d64b`, `c8091538`, release removal
      `e718147e`, production switch `6feb1223`), adapted to the current `chat` code; `run_python` and the
      presentation preview call `memoryos-interpreter` again. The Code Interpreter page (`/admin/code-interpreter`,
      `/api/chat/interpreter`) holds the Tenant switch and the health check again.
- [x] Delete core `io.memoryos.sandbox`, `SandboxTools`, `memoryos-sandbox`, `memoryos-integration-opensandbox`,
      the `admin.sandbox` page and the sandbox API; also the streamed object write only workspace archives used and
      the code event's command title.
- [x] Migration V203 dropping the sandbox tables and the Worker's sandbox sweep ([data effect](design.md#data-effect-of-v203)).
- [x] Deployment: remove `sandbox` and `sandbox-control`; build, publish and deploy `memoryos-interpreter`;
      `deploy.sh` still restores the sandbox server on a rollback to a runtime from before, and removes the one such
      a runtime left; `tools/interpreter-eval` runs on the executor again.
- [x] Documents: chat spec (code interpreter), `ARCHITECTURE.md`, remove `docs/specs/sandbox.md` and
      `docs/tests/sandbox.md`, test matrices, CI/CD runbook (interpreter runtime, sandbox removal), roadmap,
      `mem-270-chat-sandbox` and `mem-271-run-python-sandbox` moved to `superseded/`; ADR 0033 superseding ADR 0029.
- [x] Gates: `RunPythonToolTest`, `InterpreterClientTest` and `PresentationPreviewServiceTest` pass; `clean check`
      and `pnpm --dir web check` in CI.

## 3. Four modules again

Done 2026-11-22 ([ADR 0034](../../../decisions/0034-four-modules-with-memoryos-sources.md)), in one pull request with
step 2; the code moved without a change of behavior.

- [x] Merge `memoryos-integration-google`, `memoryos-integration-microsoft`, `memoryos-integration-lark` and
      `memoryos-extraction` into `memoryos-sources`, packaged by capability as at `d756db3d`
      (`io.memoryos.connector.adapter.googledrive`, `.sharepoint`, `.lark`; `io.memoryos.ingestion.extraction`), with
      one auto-configuration imports file.
- [x] One dependency rules test, `SourcesDependencyRulesTest`; `memoryos-core` keeps the check that no platform SDK
      or parser reaches its runtime classpath; the per-platform isolation goes.
- [x] ADR 0034 superseding ADR 0027 and naming ADR 0016 current again; `ARCHITECTURE.md`,
      `docs/guidelines/backend.md`, `AGENTS.md`, `README.md`, `settings.gradle.kts`, `Dockerfile`, the CI backend
      leg (`sources-worker`), the CI/CD runbook and the test matrices.
- [x] Redraw `docs/academic/sep490/diagrams/r4-package-diagram.mjs` with four modules and `memoryos-interpreter` as a
      separate Python service; HTML and PNG regenerated.
- [x] Gates: `clean check` and `pnpm --dir web check` in CI.

## 4. Release (owner's session)

- [x] Deploy to staging and run the verification walk in [design.md](design.md#verification). 2026-11-22: staging runs 0c9fb92d (V203, `memoryos-interpreter` healthy, no `channels` or `sandbox` container). A Playwright walk as an administrator opened all 35 pages (`/admin/channels` and `/admin/sandbox` now 404); as a member, Search returned 23 results, a Chat answer cited `hax-bctn-2025.pdf`, and `run_python` returned a CSV through the interpreter, with no failed API call or console error. Before V203 the staging database was dumped and the 23 sandbox archive keys listed (`~/capstone-cleanup/` on the staging host); the 23 objects were then deleted. The staging chat provider got a new Hypomnema gateway key (`memoryos-staging`) after the old one was revoked on 2026-11-19, and the dev realm's `tasco` identity provider was disabled.
- [ ] `pg_dump` production, then deploy to production with the owner's approval. Before it: confirm `/apps/memoryos/secrets/interpreter/api-key.txt` (the directory exists, root-only), list `SELECT object_key FROM sandbox_workspace_archives`; after acceptance delete those objects and any `sandbox-*` containers ([runbook](../../../runbooks/ci-cd.md#sandbox-removal)), and disable or rename the production realm's former-customer identity provider.
- [ ] Move this increment to `completed/` and reconcile the roadmap.

## Web branding (separate pull request)

- [x] Mask the MemoryOS wordmark and compact mark in the web shell and shared account states.
- [x] Use the configured provider labels and icons in the login theme and neutral examples in the web app.
- [x] Review the desktop and phone screenshots in both themes and verify the web CI gate (PR #936).
