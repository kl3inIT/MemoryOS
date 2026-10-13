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
- [ ] Tests at the PostgreSQL boundary: a turn of a service account finds only `PUBLIC` and everyone-granted
  documents, not a private Group's, and a chunk without the public flag is refused by the recheck; nobody signs
  in as it; it is absent from the lists above; a `CHANNEL` session is absent from the sidebar.
- [ ] Whether a session opens on the web by its address when it is not in the sidebar; fix it if not.
- [ ] Identity, chat and usage specs and matrices; the OpenAPI contract and the web client.

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
- [ ] 3c. The port and the registry; `channel_conversations`, `channel_identities`, `channel_messages`, and
  the Worker's daily task that deletes `channel_messages` older than ninety days, through
  `ChannelWorkerComponents`. The inbound service: the rules, the sender, who the turn runs as and the asker's name it is told, the
  conversation, the turn and its reply; the order within a conversation and the sender's rate.
- [ ] 3d. `channel_connection_state`, the supervisor and `ChannelRuntimeComponents`. `memoryos-channels`: the application, its configuration, its dependency rule test, the bound of 16 turns;
  `Dockerfile` target, the compose service with its health check and secret file, the CI leg and area filter,
  `deploy.sh` and the deployment tests.
- [ ] `ModulithArchitectureTest` counts the module.
- [ ] Tests with an in-test adapter: each rule's refusal, a duplicate, a second asker in a group, two messages of
  one conversation in order, the sender's rate, a reply continuing an old conversation, a new group message
  starting one, the idle window of a direct message, `/new`, a failed turn, restart recovery.

## 4. The Lark adapter and the administrator's API

- [ ] Settle on the test tenant what the design lists as not exercised (a group message naming the bot, `@all`,
  a slow handler, the streaming reply with sources and a footer, a reaction, the client's threads after close,
  the mark of an external group) and record it in the design; keep `LarkChannel` or fall back.
- [ ] The adapter in `memoryos-integration-lark`, with recorded events as fixtures; the rule test between the bot
  and connector packages; `checkRuntimeClasspathIsolation` unchanged.
- [ ] `/api/channels/lark` with its contract records; `openapi.yml` and the web client regenerated.
- [ ] Whether a member can change the `email` Lark reports, settled on the test tenant; the adapter reads only
  an address that is safe to match an account by.
- [ ] The wording of every notice and the reaction, taken from what Onyx, WeKnora, Dust and Claude in Slack
  show for the same cases, in both languages, for review.
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
