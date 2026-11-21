# Capstone cleanup plan

Each step is one pull request to `main`, run as one Hypomnema Task on `memoryos`. The steps run one after another
because each changes `settings.gradle.kts` or adds a Flyway migration. Every pull request is reviewed before the
merge; none turns on auto-merge.

## 0. Archive (owner's session)

- [ ] Tag `archive/pre-capstone-cleanup` on `main` and push the tag.

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

- [ ] Restore the interpreter client and its wiring removed by MEM-271 (`16e0d64b`, `c8091538`, release removal
      `e718147e`, production switch `6feb1223`), adapted to the current `chat` code; `run_python` and the
      presentation preview call `memoryos-interpreter` again.
- [ ] Delete core `io.memoryos.sandbox`, `SandboxTools`, `memoryos-sandbox`, `memoryos-integration-opensandbox`,
      the `admin.sandbox` page and the sandbox API.
- [ ] Migration dropping the sandbox tables.
- [ ] Deployment: remove `sandbox` and `sandbox-control`; build, publish and deploy `memoryos-interpreter`.
- [ ] Documents: chat spec (code interpreter), `ARCHITECTURE.md`, remove `docs/specs/sandbox.md`; a new ADR
      superseding ADR 0029.
- [ ] Gates green; `RunPythonToolTest` and the interpreter client tests pass.

## 3. Four modules again

- [ ] Merge `memoryos-integration-google`, `memoryos-integration-microsoft`, `memoryos-integration-lark` and
      `memoryos-extraction` into `memoryos-sources`, packaged by capability as at `d756db3d`
      (`io.memoryos.connector.adapter.<provider>`, `io.memoryos.ingestion.extraction`).
- [ ] One convention for its dependency rules test; keep the check that no platform SDK reaches `memoryos-core`.
- [ ] A new ADR superseding ADR 0027 (and naming ADR 0016 as current again); `ARCHITECTURE.md`,
      `docs/guidelines/backend.md`, `AGENTS.md` module list.
- [ ] Redraw `docs/academic/sep490/diagrams/r4-package-diagram.mjs` with four modules and export the PNG.
- [ ] Gates green.

## 4. Release (owner's session)

- [ ] Deploy to staging and run the verification walk in [design.md](design.md#verification).
- [ ] `pg_dump` production, then deploy to production with the owner's approval.
- [ ] Move this increment to `completed/` and reconcile the roadmap.

## Web branding (separate pull request)

- [x] Mask the MemoryOS wordmark and compact mark in the web shell and shared account states.
- [x] Use the configured provider labels and icons in the login theme and neutral examples in the web app.
- [ ] Review the desktop and phone screenshots in both themes and verify the web CI gate.
