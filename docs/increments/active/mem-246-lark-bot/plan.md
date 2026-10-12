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
- [ ] On staging: a turn cut by a restart of the API ends within the lease and its session accepts the next
  question, and 30 simultaneous turns from the benchmark accounts all answer.

## 2. Audience, shared scope and account lookup

- [ ] `chat_session.audience` and `.surface`, set at creation and immutable; sidebar and session search list
  `WEB`; the administrator's history shows the surface.
- [ ] A `SHARED` session refuses MCP servers and files at admission.
- [ ] Search by a set of access tokens: Source selection, index filter and recheck; a `PERSONAL` turn through
  today's code, a `SHARED` turn with the empty set; the search tool chooses by the session's audience.
- [ ] List every tool of a turn that reads a document and prove each goes through the scope.
- [ ] Measure on staging what the recheck adds to a search and record it.
- [ ] `iam`: the active member whose verified email equals a given one, none when several match; an index on the
  lower-cased email.
- [ ] Tests at the PostgreSQL boundary: a `SHARED` turn of a member of a private group finds only `PUBLIC` and
  everyone-granted documents; a chunk without the public flag is refused by the recheck; a `CHANNEL` session is
  absent from the sidebar.
- [ ] Whether a session opens on the web by its address when it is not in the sidebar; fix it if not.
- [ ] Chat and connector specs and matrices.

## 3. The `channel` capability and its deployable

- [ ] `CredentialCipher` to the shared kernel.
- [ ] Migrations: `channel_connections`, `channel_connection_state`, `channel_conversations`,
  `channel_identities`, `channel_messages`.
- [ ] The port, the registry, the settings service, the inbound service, the supervisor, the daily cleanup,
  `ChannelRuntimeComponents`.
- [ ] `maintain()` and the stream flush are scheduled by components `chat` publishes for a process that executes
  turns; the API imports them.
- [ ] `memoryos-channels`: the application, its configuration, its dependency rule test, the bound of 16 turns;
  `Dockerfile` target, the compose service with its health check and secret file, the CI leg and area filter,
  `deploy.sh` and the deployment tests.
- [ ] `ModulithArchitectureTest` counts the module.
- [ ] Tests with an in-test adapter: each rule's refusal, a duplicate, a second asker in a group, two messages of
  one conversation in order, the sender's rate, the idle window, a failed turn, restart recovery.

## 4. The Lark adapter and the administrator's API

- [ ] Settle on the test tenant what the design lists as not exercised (a group message naming the bot, `@all`,
  a slow handler, the streaming reply with sources and a footer, a reaction, the client's threads after close,
  the mark of an external group) and record it in the design; keep `LarkChannel` or fall back.
- [ ] The adapter in `memoryos-integration-lark`, with recorded events as fixtures; the rule test between the bot
  and connector packages; `checkRuntimeClasspathIsolation` unchanged.
- [ ] `/api/channels/lark` with its contract records; `openapi.yml` and the web client regenerated.
- [ ] On staging, through the API: enable the bot on the test tenant; a direct message from a mapped member, from
  an unmapped one, a group message, a second message while the first is answered; restart `memoryos-channels`
  during an answer and during idle.

## 5. The settings page

- [ ] `web/src/features/channels`: the Lark bot section under administration, in both languages, following the
  Lark credential section's components.
- [ ] Screenshots reviewed at desktop and phone widths, light and dark.

## 6. Acceptance and consolidation

- [ ] On staging with the three test accounts (finance, legal, an unmapped Lark member): a direct question on a
  private document answers for its reader and refuses for the other; the same question in a group answers from
  public documents only; revoking access in Lark changes the direct answer after the next synchronization.
- [ ] A load run on staging that fixes the two bounds, with the provider's rate limit recorded.
- [ ] `verification.md`.
- [ ] `docs/specs/channel.md`, `docs/tests/channel.md`, `docs/runbooks/lark-bot.md`, `ARCHITECTURE.md`
  (deployables and capability map), the backend guideline's module table, the ADR, the roadmap; the increment
  moves to `completed/`.
