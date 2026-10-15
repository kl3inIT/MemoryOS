# Implementation plan

Design: [design.md](design.md). Nothing below has started. Each pull request is accepted when CI is green and
staging deployed it. Production is not promoted from the first pull request that adds `channel` until the web page
of step 5 is merged, so an administrator can always turn the bot off.

## 1. Chat turns in more than one process, and waiting for a turn

- [x] Remove `failOrphanedRuns`; the lease is three minutes renewed every thirty seconds. A process that starts
  ends no run; the existing lease tests cover a lapsed lease ending one.
- [x] A turn beyond the process's bound or the person's waits in arrival order; a waiting event on the stream;
  failure with the capacity code after a minute; a bounded queue. The web shows the waiting state in both
  languages.
- [x] The API's bound becomes 48, a person's 3, the stream readers 256. Chat spec and matrix; the OpenAPI
  contract and the web client for the new event.
- [x] On staging: a turn cut by a restart of the API ends within the lease and its session accepts the next
  question ([verification](verification.md#restarts-on-staging-2026-10-15)). 30 simultaneous turns from the benchmark accounts all answer: done, 32, 48 and 96 at once
  ([verification](verification.md#load-run-on-staging-2026-10-15)).

## 2. Service accounts, the session's surface and account lookup

- [x] `iam`: account type `SERVICE`; a service that creates and removes a service account (actor, self-issued
  identity and profile, active membership, Basic Group) for an owner such as a channel connection; the authority
  query admits it.
- [x] A service account is left out of the Users list and count, the people pickers, Group candidates and the
  groupless invariant, and is refused by deactivation and Group edits; the web types and pages for the new
  account type.
- [x] `usage`: a service account is outside the per-person budget, the seats and the "active users" of reports,
  and keeps its own line in costs by actor.
- [x] `chat_session.surface`, set at creation and immutable; sidebar and session search list `WEB`; the
  administrator's history shows the surface.
- [x] `iam`: the active member whose verified email equals a given one, none when several match; an index on the
  lower-cased email.
- [x] Tests at the PostgreSQL boundary: a turn of a service account finds only `PUBLIC` and everyone-granted
  documents, not a private Group's, and a chunk without the public flag is refused by the recheck; nobody signs
  in as it; it is absent from the lists above; a `CHANNEL` session is absent from the sidebar.
  `SourceSyncAccessTest` (the reads, the index filter and the recheck agree), `DefaultServiceAccountsTest` (no
  sign-in reaches it; the lists, counts, pickers and Group candidates), `ChatPersistenceIntegrationTest` (a
  `CHANNEL` session opens for its owner and is absent from the sidebar and session search). A Source cannot be
  granted to the Basic Group, so "everyone-granted" is a `PUBLIC` Source or a synchronized file open to anyone.
- [x] Whether a session opens on the web by its address when it is not in the sidebar; fix it if not. It does:
  on staging a bot's direct session opened by its address with both turns and a working composer.
- [x] Identity, chat and usage specs and matrices; the OpenAPI contract and the web client. The identity spec
  and matrix hold the `SERVICE` account, the chat spec and matrix the session's surface and the waiting turn, the
  AI usage spec and matrix the service account's budget and cost line; `openapi.yml` and the web client carry
  `AccountType` and `surface`.

## 3. The `channel` capability and its deployable

Four pull requests, each green on its own.

Staging, 2026-10-13: the API killed during a turn, the Worker ended it `CHAT_INTERRUPTED` 3 min 8 s later
(lease 3 min); 30 turns of one person sent at once all completed, in 6.6 to 33.9 s.

- [x] 3a. `maintain()` and the stream flush are scheduled by `chat`'s own configuration beside the turn
  runtime, so any process that runs turns runs them; the API no longer schedules them. Ending the runs of a
  process that died is a Worker task, which reads nothing a process holds.
- [x] 3b. `CredentialCipher` to the shared kernel. `channel_connections`, several a Tenant. The capability
  `CHANNELS_MANAGE` (backend, contract, the Groups page's copy in both languages). The connection service, which
  creates and retires the bot's service account. Each table comes with the code that writes it: the others move to
  3c and 3d.
- [x] 3c-1. The port and the registry; `channel_conversations`, `channel_identities`, `channel_messages`, and
  the Worker's daily task that deletes `channel_messages` older than ninety days, through
  `ChannelWorkerComponents`. The inbound service: the claim, the rules, the sender, who the turn runs as, the
  conversation, the turn and its reply, notices. `ChatSessionService.leaf`.
- [x] 3c-2. What 3c-1 left: the sender's rate, the order within a conversation, the reaction that acknowledges
  a message, the asker's name a bot turn is told, and the link to a direct conversation on the web (merged in #653;
  it shows once staging sets `MEMORYOS_CHANNEL_WEB_URL`). Originally: the rules, the sender, who the turn runs as and the asker's name it is told, the
  conversation, the turn and its reply; the order within a conversation and the sender's rate.
- [x] 3d-1. The model catalog, `ModelCalls`, the native Asyncer and the tool-observation sanitizer move from the
  API module into `chat.execution`, so every process that runs turns has them. `ChannelAdapter.open` and
  `ChannelTransport`; `channel_connection_state`; `ChannelSupervisor`.
- [x] 3d-2. `ChannelRuntimeComponents` and the module `memoryos-channels`: an application with no HTTP API (health
  on 8082), composed from what modules publish as the Worker is, no Flyway (schema validated, started after the
  API); 16 turns, no per-person bound; a context test with an in-test adapter; its dependency rule test;
  `io.memoryos.channels` added to core's rule against deployables.
- [x] 3d-3. The `Dockerfile` target; the compose service (health check, egress, memory limit 1.5 GiB, one
  replica) in the staging overlay (staging only until acceptance); the API's Redis user and object-storage
  identity, whose rights are a turn's; the channel key, mounted in the API too; the database budget counts the
  pool (15 + 10 + 5 + 10 fits the limit of 40, so it stays); the CI leg, image build and every
  component list; `deploy.sh` with rollback to a release without it; the deployment tests; an alert and the
  dashboard's service filter. Staging only until acceptance.
- [x] `ModulithArchitectureTest` counts the module.
- [x] Tests with an in-test adapter (3c-1); the sender's rate and the order of a conversation come with 3c-2.

## 4. The Lark adapter and the administrator's API

- [ ] Settle on the test tenant what the design lists as not exercised (a group message naming the bot, `@all`,
  a slow handler, the streaming reply with sources and a footer, a reaction, the client's threads after close,
  the mark of an external group) and record it in the design; keep `LarkChannel` or fall back.
- [x] The adapter in `memoryos-integration-lark` (`LarkChannelAdapter`): the long connection, the conversion of
  a message with both organisations read from the event, the streaming reply, notices, the sender's e-mail and the
  external-chat check; `checkRuntimeClasspathIsolation` unchanged. It shares the connector's package, because both
  use its bounded `LarkTransport`.
- [x] `/api/channels` with its contract records (list with status, test, add, replace the secret, switch,
  remove), each app checked with Lark before it is stored; `openapi.yml` and the web client regenerated.
- [ ] Whether a member can change the `email` Lark reports, settled on the test tenant; the adapter reads only
  an address that is safe to match an account by.
- [ ] The wording of every notice and the reaction, taken from what Onyx, WeKnora, Dust and Claude in Slack
  show for the same cases, in both languages, for review. Ready for review in the design's
  [notices](design.md#notices).
- [ ] On staging, through the API: enable the bot on the test tenant; a direct message from a mapped member, from
  an unmapped one, a group message, a second message while the first is answered; restart `memoryos-channels`
  during an answer and during idle.

## 5. The settings page

- [x] `web/src/features/channels`: "Kênh chat" under administration (`/admin/channels`), in both languages,
  listed and opened as the MCP servers now are (the shared `AppRow`, a page a bot), the add dialog following the
  Lark credential dialog.
- [x] Screenshots reviewed at desktop and phone widths, light and dark (`channels-administration.spec.ts`).

## 6. Acceptance and consolidation

- [ ] On staging with the three test accounts (finance, legal, an unmapped Lark member): a direct question on a
  private document answers for its reader and refuses for the other; the same question in a group answers from
  public documents only; revoking access in Lark changes the direct answer after the next synchronization.
- [x] A load run on staging that fixes the two bounds, with the provider's rate limit recorded
  ([verification](verification.md#load-run-on-staging-2026-10-15)): no rate limit at 24 at once; the provider's
  limit of about 160 then set the bounds to 80 in the API and 24 in the bots' process.
- [ ] `verification.md`: the load run, the provider's limit and the restarts are recorded; the three-account
  acceptance is not.
- [ ] `docs/specs/channel.md`, `docs/tests/channel.md`, `docs/runbooks/lark-bot.md`, `ARCHITECTURE.md`
  (deployables and capability map), the backend guideline's module table, the ADR, the roadmap; the increment
  moves to `completed/`. All but the move are done ([ADR 0028](../../../decisions/0028-chat-channels-in-their-own-process.md));
  the increment moves once the acceptance above is run.
