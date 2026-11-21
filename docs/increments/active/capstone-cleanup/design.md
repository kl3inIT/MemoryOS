# Capstone cleanup: back to the Report 3 scope and the original module layout

## Why

MemoryOS now serves only the SEP490 capstone (owner, 2026-11-22: the customer project ended; defence on
2026-12-21). The scope is Report 3: FE-01 to FE-07, UC-01 to UC-34, JOB-01 to JOB-12, API-01 to API-05. Report 3
names no chat bot, channel, Zalo, WhatsApp or sandbox, and the Report 4 diagrams already leave them out
(`docs/academic/sep490/diagrams/README.md`).

The drift started on 2026-10-12/13, the day after the SRS froze (2026-10-11):

- On 2026-10-11 the owner agreed that integrations stay in one `sources` module ([ADR 0016](../../../decisions/0016-integration-bundle-named-sources.md)).
- On 2026-10-12 the module layout split by platform ([ADR 0027](../../../decisions/0027-integrations-by-platform-and-memoryos-module-names.md),
  MEM-247); its stated context is "A Lark bot is coming beside the Lark connector".
- From 2026-10-13 to 2026-10-22 the chat bots (MEM-246, MEM-263, MEM-283, MEM-285, MEM-298, MEM-299) and the
  OpenSandbox sandbox (MEM-270, MEM-271) followed, with seven modules and a Go bridge.

The owner names the drift as the module split, not only the bots. Bot and personal-assistant work moves to the
owner's Hypomnema platform, which already has Lark, WhatsApp and Zalo bots.

## Decisions (owner, 2026-11-22)

| Area | Decision |
|---|---|
| Kept | Everything not named under Removed, including every improvement made since 2026-10-12 (citations and highlights, guardrail, web search, compaction, prompt cache, MCP catalog and tool policy, library, projects, meeting minutes, the team's fixes). This is a forward cleanup, not a revert to 2026-10-11; step 3 moves code without changing behavior. Named explicitly: agents (including the Agent Builder), deep research, image generation and editing, voice, meetings, MCP, the Lark **connector** (a Source) |
| Code execution | Kept, through `memoryos-interpreter` (Onyx python-sandbox, MEM-110) as before MEM-271; the OpenSandbox sandbox is removed |
| Removed | `memoryos-channels`, core `channel`, `memoryos-integration-zalo`, `memoryos-integration-whatsapp` (with its Go bridge), the Lark bot (`LarkChannelAdapter`, `LarkReplyCard`), core `sandbox`, `memoryos-sandbox`, `memoryos-integration-opensandbox`, the web pages `admin.channels` and `admin.sandbox` |
| Module layout | Back to four Gradle modules as at `d756db3d` (2026-10-11): core, sources, api, worker. `sources` holds every platform client and adapter and content extraction, packaged by the capability each serves (ADR 0016) |
| Branching | Work lands on `main` through pull requests; no long-lived capstone branch. Tag `archive/pre-capstone-cleanup` on `main` before the first removal |

### Module names

The four modules keep the `memoryos-` prefix that ADR 0027 introduced: `memoryos-core`, `memoryos-sources`,
`memoryos-api`, `memoryos-worker`. The drift was the split, not the names; renaming the directories back would
touch every Dockerfile, workflow, runbook and the Report 4 figures for no change in structure.

### Target layout

```text
memoryos-core      capabilities under io.memoryos.<capability>; no channel, no sandbox
memoryos-sources   io.memoryos.connector.adapter.<provider> (googledrive, sharepoint, lark),
                   io.memoryos.ingestion.extraction (Tika, Docling, OCR readers, spreadsheet and native readers)
memoryos-api       HTTP, security, contracts
memoryos-worker    Redis Streams and db-scheduler composition
memoryos-interpreter  Python service for run_python, built and deployed again
web, landing       unchanged deployables
```

The rule of ADR 0027 that fails the build when a platform SDK reaches `memoryos-core`'s runtime classpath stays:
it guards `core`, not the split. The per-platform isolation check between integrations goes.

## What removal touches

- **Database.** New migrations from V202 drop the channel and sandbox tables and columns created by V157, V158,
  V160, V162, V163, V165, V167, V168, V170, V179, V183 (`chat` channel binding), V184, V185, V187, V192, V193, V194
  and V196 (`chat` preferences channel persona). Applied migrations are never edited.
- **Other capabilities.** `chat` (channel binding, the persona of direct messages, `RunPythonTool`, `SandboxTools`,
  `PresentationPreviewService`, `ChatModelExecutor`, `ChatExecutionConfiguration`), `library` tests, the Worker
  (`MemoryOsWorkerApplication`, `ControlPlaneConfiguration`), the agents editor's channels panel, chat settings,
  audit actions and Group capability copy in `web`.
- **API.** Channel and sandbox controllers and contracts; `openapi.yml` and the Hey API client are regenerated.
- **Deployment.** `channels` in `compose.staging.yaml` and `compose.search.staging.yaml`; `sandbox` and
  `sandbox-control` in `compose.base.yaml`; their secrets, images and CI jobs; `memoryos-interpreter` comes back
  into the release and the deployments.
- **Documents.** `ARCHITECTURE.md`, `docs/specs/channel.md` and `sandbox.md` (removed), `chat.md`, the test
  matrices, the runbooks (`lark-bot.md`), `docs/roadmap.md`, the active increments of removed work
  (`mem-246-lark-bot`, `mem-283-whatsapp-bot`, `mem-265-chat-schedules` when it depends on channels) and a new ADR
  that supersedes ADR 0027, 0028, 0029 and 0031. The Report 4 package diagram (`r4-package-diagram.mjs`) is redrawn
  with four modules.

### Data effect of V202

Per [ADR 0018](../../../decisions/0018-schema-changes-preserve-data.md), with the owner's decision of 2026-11-22 to
drop the bots' data:

- **Lost on purpose.** Every bot and its encrypted secret, the provider identities and account links, link codes,
  conversation and message records the bots kept (ids only; the text is in the conversations), group chats and their
  labels and permissions, pairing codes, connection state and leases, the WhatsApp bridge's `whatsapp` schema with its
  linked devices and whatsmeow sessions, the bindings of bots and group chats to assistants, the assistant a member
  chose for direct messages, `mcp_server.public_bots`, the `CHANNELS_MANAGE` grants (their Tenants'
  `authorization_version` advances) and the scheduled task `memoryos-channel-message-retention-v1`.
- **Kept.** The conversations the bots held (`chat_session.surface = 'CHANNEL'`, with their messages), which stay their
  owners' and in the administrator's chat history; the bots' `SERVICE` accounts, which those conversations and the
  audit log name; audit events with `channel.*` actions; `V155` and `V156` themselves.
- **Rollback.** The release before cannot run on the new schema (it reads the dropped columns), so going back past
  V202 is a database restore.

## Web branding

The web shell, account states and 404 share the MemoryOS brand. The full wordmark reuses the splash and
loader artwork, and the desktop rail uses the favicon's M. Both are masks painted in the active theme's
text colour. The mobile navigation opens as a drawer and closes back to its trigger.

The login theme styles the provider controls rendered by Keycloak using their configured display names and
icons.

## Verification

- `./gradlew clean check` green, including `ModulithArchitectureTest`, `CoreDependencyRulesTest`,
  `WorkerDependencyRulesTest` and `OpenApiContractTest`; `pnpm --dir web check` green.
- Flyway migrates a copy of the staging schema forward without error.
- Staging: sign in, a Source syncs and indexes, Search and a cited Chat answer work, `run_python` returns a file,
  a meeting records; no `channels` or `sandbox` container runs.
- Production is deployed only after a `pg_dump` taken just before, with the owner's approval.

## Out of scope here

Moving embeddings to OpenAI and OCR to a catalog model before the GPU serving node is returned is server
configuration, done step by step by the owner's session, not in these pull requests.
