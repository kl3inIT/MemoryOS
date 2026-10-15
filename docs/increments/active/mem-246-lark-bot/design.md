# Lark bot

Status: **design accepted 2026-10-13; nothing implemented.** Linear:
[MEM-246](https://linear.app/memory-os/issue/MEM-246). It follows the Lark Suite connector
([MEM-245](../../completed/mem-245-lark-connector/design.md)) and the platform modules of
[ADR 0027](../../../decisions/0027-integrations-by-platform-and-memoryos-module-names.md).

## Problem

People at a Lark organisation ask their questions in Lark. Today they must open MemoryOS in a browser. A bot in
Lark answers there, from the same documents and under the same access rule as the web chat, and is the first of
several chat channels (Telegram, Zalo, Slack, Teams).

## What a person does

An administrator, once: creates a custom app in the Lark Developer Console with the bot feature, the scopes of the
[runbook](#documents), the event `im.message.receive_v1` on a persistent connection, and publishes it; then enters
its App ID and App Secret in MemoryOS and enables the bot. MemoryOS shows whether the connection is open.

A member installs nothing and signs in to nothing in Lark:

- **Direct message.** They open the bot's chat and type. The answer streams into a card, with the sources it cites.
- **Group.** Someone adds the bot to the group once; a member names the bot (`@bot`) with a question. The answer is
  posted in the group as a reply to that message.

The one condition is that their Lark `email` equals the verified email of their MemoryOS account.

## Behaviour rules

1. **In a direct message the bot is the asker; in a group it is itself.** Two identities, as Claude and ChatGPT
   in Slack, Rovo, Glean, Notion, Aily and Onyx have them:
   - A direct message from a person with a MemoryOS account runs as that person and reads what they may read,
     exactly as the web chat does.
   - A group question runs as the **bot's own account**, a member of the Tenant that is in no ordinary Group. By
     the access rules that already exist it reads what every active member may read: documents of a `PUBLIC`
     Source, and documents of a `SYNC` Source whose provider grants everyone. A group answer ends with one line
     inviting a direct message for an answer under the asker's own access.
   - This is the scope an administrator already controls: making a Source `PUBLIC` is the decision that the bot
     may answer from it in a group, as Rovo's shared account and Glean's Public Mode are set by an administrator.
   - A department's group is therefore not served from its private documents, although every member may read
     them; its members ask in a direct message. Reading what every member of a group may read (Microsoft's rule)
     is the known extension and is left to its own issue, decided once real use shows which groups ask.
   - What the bot may use in a group is what its account is given, and nothing of the asker's. A question that
     needs something of the asker's own (their mailbox, a connection they signed in to) is answered with an
     invitation to a direct message, as Lindy, Copilot Studio and Aily do.
2. **Only members of this Lark organisation.**
   - A sender whose `tenant_key` differs from the organisation the event names for the bot's app is told the bot
     serves the organisation only. A custom app belongs to one organisation, which every event's header names, so
     the check needs no stored key and no scope to read the tenant.
   - A group Lark marks external is refused whole, also for a member asking in it: outsiders read the answer.
   - A member with no matching MemoryOS account is still answered, as the bot: in a group like anyone, and in a
     direct message with a line saying that signing in to MemoryOS with that email opens their own documents.
     Onyx answers such a person as its anonymous user.
3. **The bot answers when called.** A direct message, or a group message that names the bot. `@all` does not call
   it. It needs only the scopes `im:message.p2p_msg:readonly` and `im:message.group_at_msg:readonly`, so Lark does
   not deliver other group messages at all.
4. **An answer names its sources.** The sources whose citation the answer uses, with title and link, as the web
   chat shows them. In a group every source is one all members may open.
5. **Text only in the first release.** An image, file or other message type gets a short notice. No re-share
   button, no feedback buttons, no proactive message.

The rules were checked against Onyx's Slack bot and the documented behaviour of Glean, Microsoft 365 Copilot,
Rovo, Moveworks, Guru, Agentforce, Dust, Notion and Lark Aily (2026-10-12):

| Rule | Who does the same | Alternatives seen |
| --- | --- | --- |
| Direct message under the asker's access | All of them | — |
| Group answer from organisation-wide content | Onyx, Glean Public Mode, Guru's "All Members" switch | Private reply to the asker (Glean default, Rovo); answer by direct message (Moveworks); post only if every member may open the sources, else the asker previews and approves (Microsoft) |
| External group refused | Glean, Microsoft, Agentforce, Aily | — |
| Unmapped member answered as the bot | Onyx | No answer (Glean); a visible "not a member" reply (Dust) |
| Answer only when named | Microsoft, Agentforce, Aily default | Confidence-gated automatic answers (Glean, Guru, Moveworks) |
| The bot's own identity in a group | Claude and ChatGPT in Slack, Rovo's shared account, Notion, Aily, Onyx's anonymous user | The asker's identity (Microsoft, Dust, Glean's private reply) |
| The asker's own things out of a group | Lindy, Copilot Studio, Aily default, OpenAI workspace agents | A consent asked of the asker for each use (Claude, ChatGPT, Grok Team Bots); the asker previews before it is posted (Microsoft); used without asking (Dust) |

Lark's "visible only to you" card is not used for a private reply in a group: it reaches only clients that are
online, sends no notification and does not work in topic groups.

The decided next step, not built now, is the consent Grok Team Bots ask: when a group question needs a personal
connection, the bot asks that person, in a direct message since Lark has no private reply in a group, with "allow
once", "always allow" and "skip", names the account it will use, and the choice is cleared in the person's
settings. The turn then runs as the bot with that one grant from the asker. The identity a turn runs as and the
grants lent to it are kept apart from the start so that this is an addition.

Also recorded for the time the bot gains actions: the approver of an action is the requester, checked by the
`open_id` of the person who pressed the button (Dust accepts any clicker); a message from another person in the
same thread is information, not an instruction; nothing said in a direct message is recalled in a group.

## Evidence from the test tenant (2026-10-12)

| Question | Answer |
| --- | --- |
| Does a persistent connection work on Lark international? | Yes. `com.lark.oapi.ws.Client` with domain `https://open.larksuite.com` connected from the staging host, and the console accepted "persistent connection" only while it was connected |
| What does a received message carry? | `event_id`, `message_id`, `chat_id`, `chat_type` (`p2p`), message `type`, `root_id`, `thread_id`, the sender's `open_id` and `tenant_key`, the mentions |
| Can the app send and stream? | A text message, a CardKit card created, sent by `card_id`, updated three times by element with a rising `sequence` and closed by turning `streaming_mode` off, and a reaction all succeeded with the scopes held |
| Can the app read the sender's email? | Yes, `contact/v3/users/{open_id}` with the scopes the connector already holds |
| Does the SDK's bot layer connect? | `com.lark.oapi.channel.LarkChannel` (SDK 2.8.5) connected and resolved the bot's `open_id` |
| JDK warning | The shaded protobuf of the WebSocket client warns about `sun.misc.Unsafe` on JDK 24 and later when the connection opens; REST calls never load it |

Not exercised: a group message naming the bot, a handler slower than Lark's three seconds, `LarkChannel`'s
streaming reply, a closed client's threads, how Lark marks an external group. The owner ended probing on
2026-10-13; these are settled while the adapter is built ([plan](plan.md) step 4) and on staging.

Lark's documented limits: 50 connections an app, each event delivered to one connection chosen at random, three
seconds to acknowledge, persistent connections for custom apps only (a store app needs a webhook).

## Design

### Placement

| Module | Holds |
| --- | --- |
| `core` `io.memoryos.channel` (new capability, `CLOSED`; depends on `shared`, `iam`, `chat`, `chat::streaming`, `audit`) | The provider enum, the adapter port and its registry, the connection settings service, the inbound service (rules, identity, conversation, turn, reply), the supervisor, `persistence/`; `ChannelRuntimeComponents` for the process that runs it |
| `core` `io.memoryos.iam`, `io.memoryos.chat`, `io.memoryos.usage` | Service accounts and the lookup of an account by verified email; the surface of a session; a service account outside the per-person budget and counts |
| `memoryos-integration-lark` `io.memoryos.integration.lark` | The Lark adapter of the port, on the SDK's `LarkChannel`, with its own package apart from the connector's gateway |
| `memoryos-channels` (new deployable, `io.memoryos.channels`) | The process that opens the connections, receives messages and runs their turns: `channel`, `chat` with its turn execution, and the integrations |
| `api` `io.memoryos.api.channel` | The administrator's settings controller and its `contract/` records |
| `web` `features/channels` | The settings page |

`channel` does not depend on `connector`, and the bot does not use the connector's credential: a Tenant may run the
bot without a Lark Source, and a Source without the bot. The two may name the same Lark app.

### A deployable of its own

| Product | Where the bot runs | How it reaches the conversation |
| --- | --- | --- |
| Onyx | Its own process: a Kubernetes Deployment (`slackbot.yaml`), a supervisord program in the background container under Compose | In process: it imports the chat pipeline and holds its own database pool |
| Dust | Its own service (`connectors`) | Over HTTP: the conversation service's public API with a workspace system key and a header naming the user to act as |
| WeKnora, RAGFlow, OpenClaw, Hermes Agent | The process that runs the agent | In process |

The bot runs in `memoryos-channels`, apart from the API, in Onyx's shape:

- a failure of the provider's client (the SDK leaves threads behind on close and needs supervision) or a burst of
  bot traffic cannot take the web application down, and each side has its own bound on turns;
- a hosted deployment holds one connection a Tenant, which is not work for the process serving browsers;
- the turn is called in process through the same `chat` API the web uses. Dust's shape needs a credential that may
  act as any member and an HTTP contract for it; that surface is not opened.

The API and `memoryos-channels` never call each other. They share PostgreSQL and Redis: the administrator's
settings are rows the channels process reconciles, and the connection's state is a row it writes. The cost is one
more JVM with the chat and model stack on each host, and the prerequisite below.

### Chat turns in more than one process

A turn's stream already goes through Redis and its row carries a lease the owning process renews, with
reconciliation of a lapsed lease. Two things assume one process and change:

- `ChatTurnService.failOrphanedRuns` fails every running turn at start. With a second process it would fail the
  other's turns, so it is removed; a turn left by a dead process ends when its lease lapses. The lease becomes
  three minutes, renewed every thirty seconds, so a session is not held for half an hour. Delivered in plan
  step 1.
- The scheduling of `maintain()` and of the stream flush lives in the API module. A tick that needs what a process
  holds in memory (its replies' buffers, its runs' leases, its queue) moves into `chat` beside the turn runtime, so
  every process that executes turns runs it. Ending the runs of a process that died reads only PostgreSQL, so it
  becomes a Worker task, run once for all processes, as the Worker already purges and retains conversations.
  Delivered in plan step 3a.

Stop, delete and subscribe stay local to the process that owns the turn. A channel session is not listed on the
web, so the API never stops a turn the channels process runs. This is also the step that lets the API itself run
as several replicas later; that is not part of this increment.

### Waiting for a turn

Today a turn beyond the process's bound is refused at once (`CHAT_CAPACITY_EXCEEDED`), on the web as well. It
changes for both: the command is accepted, the reply is reserved, and the run waits its turn in arrival order; the
stream says it is waiting, and the web shows that instead of an error. A run that waits longer than a minute fails
with the capacity code.

What a person meets is their own bound: three turns at once (LibreChat ships two), the fourth waiting. The
process's bound stays as a backstop, not a throttle: 48 turns in the API and 16 in `memoryos-channels`
(`memoryos.chat.execution.concurrency` of each), where it was 8. Without one, a burst reaches the provider's rate
limit, the database pool, the stream readers and the spend limit (which counts settled turns only) with worse
failures than waiting. LibreChat and Onyx set no such bound and are held by their own resources. The numbers are a
starting point: a load run on staging raises them to what the host and the provider take, and the database pool
(15) and the stream readers (256) with them.

### The port

One adapter a provider, registered by `ChannelProvider` (`LARK`), from the first provider:

- `open(connection, receiver)` starts the provider's transport for one connection and returns a handle with
  `connected()` and `close()`;
- the receiver is called with a normalized message: provider, external message id, chat id, chat kind (`DIRECT`
  or `GROUP`), sender id, the sender's organisation key, whether the bot was named, the text with the bot's mention
  removed, the message type;
- `reply(connection, target)` opens a streaming reply to one message, with `append(text)`, `finish(sources,
  footer)` and `fail(notice)`; `notice(connection, target, text)` sends a short message;
- `sender(connection, senderId)` answers the sender's email; `chat(connection, chatId)` answers whether a group is
  external;
- `verify(draft)` checks a credential and answers the bot's name, its id, the organisation key and missing scopes.

The adapter owns the transport, the provider's ids and the shape of a reply. Rules, identity, conversations,
deduplication and the turn are the capability's. Capability flags and a lock for single-consumer transports are
added with the provider that needs them.

### Connection

`channel_connections`: `tenant_id`, `id`, `provider`, a name the administrator gives the bot, the app id and
region, the encrypted secret envelope, `enabled`, the language of the bot's notices (`vi` or `en`), the bot's
service account and a revision; the bot's external id and the provider organisation key are added with the
adapter that reads them. A Tenant may have several bots, as Onyx keeps several Slack bots and WeKnora several IM
channels, each bound to an agent; one provider app serves one bot. A new bot is switched off; switching it off
again keeps its account, and removing it retires the account. `CredentialCipher` moves unchanged from the connector's
persistence package to the shared kernel; the envelope's kind is `CHANNEL_LARK` and its key is
`memoryos.channel.credential-encryption-key`, a secret file like the connector's.

A `ChannelSupervisor` (`SmartLifecycle`, in `channel`, started only by `memoryos-channels`) opens every enabled
connection when the application has started, and every thirty seconds reconciles: a connection enabled, disabled,
changed or no longer `connected()` is opened, closed or reopened. It writes the connection's state (open since,
last failure code, last message time, a heartbeat) to `channel_connection_state`, which the administrator's page
reads. A failed open is recorded with its typed code and retried on the next pass; an authentication failure
disables nothing but is shown. Stopping the application closes the connections first.

Lark allows an application fifty long connections and gives each event to one of them at random, never to all
(its documentation of the persistent connection, read 2026-10-13), so a second replica of `memoryos-channels`
would not answer twice. Nothing therefore records which process holds a connection; what must be shared between
replicas, deduplication and the order within a conversation, is in PostgreSQL. One replica is deployed.

### Receiving a message

The adapter acknowledges the event to Lark at once and hands the message to a virtual thread: Lark pushes an
event again when its handler has not returned within three seconds, up to four more times over about seven hours
(15 seconds, 5 minutes, 1 hour, 6 hours). The inbound service then, in order:

1. inserts `channel_messages (connection_id, external_message_id)`; a duplicate ends here. The key is the
   message's id and not the event's, as Lark's page for `im.message.receive_v1` requires;
2. applies rule 2 and rule 5 from the message alone (organisation key, message type), then asks the adapter whether
   a group is external (remembered on the conversation for an hour);
3. resolves the sender: `channel_identities (connection_id, external_user_id)` holds the actor, or that there is
   none, for an hour; otherwise the adapter reads the email and `iam` answers the one active member whose
   verified email equals it. No match, or more than one, is no account. Matching by e-mail is what Onyx and Dust
   do, and it trusts the address the provider reports: Dust's own code marks it as open to spoofing. Lark keeps
   `email` unique within an organisation, but nothing read so far says whether a member can change their own;
   plan step 4 settles that on the test tenant before the adapter reads it, and uses only an address the
   organisation's administrator controls if a member's own is not safe;
   what Lark publishes (read 2026-10-15) settles it in part: the contact `email` is unique within an organisation
   (`邮箱不可重复`), so nobody can take the address of a person who has it in Lark, and it is the administrator who
   assigns and changes it, with a field setting deciding whether members may edit a field. What is left is a
   MemoryOS account whose address belongs to nobody in that Lark organisation, open only where the administrator
   lets members edit `email`; the test tenant's own setting is to be read in its admin console;
4. chooses who the turn runs as: the sender's account in a direct message when there is one, the bot's account
   in a group and for a sender without an account; then finds the conversation and its chat session (below);
5. starts the turn with `ChatTurnService.command` (`SEND`, the session's current leaf, a request id derived from
   the row) and streams it: a reader on the turn's stream appends text to the reply, and at the outcome the final
   message is read from history for its sources.

The bot puts a reaction on a message only while it waits for the turn its conversation is already running, as
spring-agent's Feishu bot does with `Get`, so a person sees the question was taken while it waits. A message that
does not wait is acknowledged by the reply card itself, opened within about a second and a half; a reaction on it
too would cost a Lark call and say nothing more (changed 2026-10-14, after the card replaced the SDK's stream). Messages of one conversation are
handled one at a time: the second waits for the first turn to end, up to the waiting bound, instead of being
refused. Nobody answers two messages of one conversation at once: Dust, WeKnora and RAGFlow queue the second as
this does, and OpenClaw, Hermes Agent and spring-agent feed it into the running turn, which is left for later. A
sender is held to ten messages a minute and told so, as WeKnora does for its Lark bot; Dust's own bound is a
hundred a minute, and Onyx, RAGFlow, Hermes Agent and OpenClaw set none for a sender. What binds first is Lark's
own limit of five messages a second to one chat and five updates a second to one card.

The reply quotes the question (Lark's reply call without `reply_in_thread`), as spring-agent, WeKnora, RAGFlow and
OpenClaw's default do on Lark; a thread is Slack's habit. A reply to a message inside a Lark topic lands in that
topic by Lark's own rule.

A turn that runs as the bot is told the asker's display name, so the answer addresses the right person, and never
their e-mail; the name is given as a label of the message and not as an instruction, as OpenClaw fences a sender's
name. Onyx, Dust and OpenClaw pass the name, Dust the e-mail too.

Every refusal and failure is a notice in the configured language and a recorded reason; none carries a provider
message, a backend failure text or the question's text. A chat failure maps by its typed code: capacity becomes
"busy, ask again in a moment", a spend limit names the limit, anything else a general failure. A reply in a
direct message that ran as the sender ends with a link to the same session in MemoryOS.

A row left `RECEIVED` by a process that stopped stays so: the message was claimed and is not answered again, and
Lark's later pushes of it are dropped at the claim.

### The bot's account

`iam` gains service accounts: an actor of account type `SERVICE`, an active member of the Tenant in the protected
Basic Group and in no other, created and removed with the channel connection that owns it.

- **Identity.** It is bound to an identity MemoryOS issues itself (issuer `urn:memoryos:service`, subject the
  connection's id) with a profile carrying the bot's name and no email. No identity provider signs a token for
  that issuer, so nobody signs in as it, and every place that already shows an actor's name (the administrator's
  chat history, AI costs, audit, usage reports) shows the bot's without a change.
- **Authority.** Basic access gives it what a turn checks (`CHAT_WRITE`, `SEARCH_READ`, the spend check). The
  authority query admits `SERVICE` beside `STANDARD`. It holds no ordinary Group, no share and no personal
  connection, so it reads `PUBLIC` Sources and everyone-granted documents, uses the Tenant's default agent and
  public model providers, and could use only MCP servers the administrator connected for everyone.
- **Not a person.** It is left out of the Users list and its count, the people pickers and Group candidates, the
  seats and "active users" of usage reports, and the budget applied to each person; it cannot be deactivated or
  put in a Group from the Users and Groups pages. Its spend counts in the Tenant's budget and shows as its own
  line in costs by actor. A budget for the bot alone is not added; the sender's rate bounds a single person.
- **Language.** Its interface language is the connection's, so a refusal the turn writes is in the language of
  the bot's notices.

Putting the bot's account in an ordinary Group would widen what it answers from in every group, as widening
Rovo's shared account does. It is not offered now.

### Conversation and session

A message continues a conversation by what it replies to before by when it was sent:

| Message | Session |
| --- | --- |
| A reply to a message of a conversation (Lark's reply or thread) | That conversation's, however old |
| A new message in a group | A new one |
| A new message in a direct message | The last one's while it is less than an hour old (`memoryos.channel.conversation-idle`), else a new one |
| `/new` | A new one, and the next message starts in it |

`channel_messages` maps every message of a conversation, the bot's replies included, to its session, which is how
a reply is resolved; `channel_conversations (connection_id, external_chat_id, external_user_id)` holds the current
session of a direct message. In a group a reply by another member continues the same session: it runs as the bot,
so nothing of the first asker's is lent, and a thread is shared in Dust and Hermes Agent too.

Onyx and Dust key on the thread and start anew outside it; a session that never ends until `/new` is Hermes
Agent's and OpenClaw's default, which suits one operator's long work and not questions whose earlier turns shape
the search. The hour is Claude's fallback in Slack for messages outside a thread. A question that follows another
in a group without replying to it loses the context; the notice a group answer ends with says to reply to
continue. If Lark's events do not carry the replied message reliably, the rule falls back to the idle window
alone, per asker, and this section is corrected.

The session is an ordinary chat session on the Tenant's default agent, owned by whoever the turn runs as: the
sender, or the bot. `chat_session` gains one immutable column, `surface` (`WEB`, `CHANNEL`): the web sidebar and
session search list `WEB` only, and the administrator's chat history lists both, as Onyx keeps its bot's
sessions out of a person's list (`onyxbot_flow`) and in the query history. Retention and deletion apply
unchanged. `channel_messages` keeps who asked, since a group turn's session names the bot.

A row of `channel_messages` holds ids only (the provider's message, chat and sender, the session and the turn's
messages) and a refusal's reason; the text of a question and of its answer is in the chat session and nowhere
else. Rows older than ninety days (`memoryos.channel.message-retention`) are deleted by a daily Worker task; an old
reply then starts a new conversation. Onyx keeps only the thread's id on its chat session; spring-agent, WeKnora
and RAGFlow keep message ids for deduplication alone; Dust keeps the message's text and the sender's Slack
profile, which this does not.

Usage is recorded under the account the turn ran as. In `memoryos-channels` a person's bound on turns equals the
process's, because every group turn is the bot's.

### Lark adapter

`LarkChannel` is used for the transport, the normalization, the mention rule and the streaming reply, because the
maintained SDK already has them; it is given `LarkTransport` as its HTTP transport, the connection's domain, and
`requireMention`. Its in-memory deduplication stays on and is not relied on. Its chat queue is switched off: it
orders by chat and merges messages sent close together, so one member's question would hold back and blend with
another's in the same group, where this design orders by conversation. If building the adapter finds it
unfit (an event redelivered while the handler is slow, a reply that cannot carry the sources, threads left after
`disconnect`), the adapter uses `com.lark.oapi.ws.Client` with `EventDispatcher` and the CardKit calls already
proven, behind the same port.

The module's classpath rule is unchanged; a rule test keeps the bot package and the connector package from
importing each other.

### Administration

`/api/channels/lark`: read the connection and the state the channels process last wrote (open, last failure code,
last message time; stale when the heartbeat is old), create or replace the credential, test it, enable or disable,
delete. A change takes effect at the supervisor's next pass. It requires `CHANNELS_MANAGE`, a new assignable
administrative capability beside `SOURCES_MANAGE`, `MODELS_MANAGE` and `MCP_MANAGE`, which the Tenant's
administrators hold and an ordinary Group can be granted; changes are audited. The page lists the scopes and the
console steps beside the
form and saves a switch on change.

### Observability

Events `channel.connection.opened`, `.closed`, `.failed`, `channel.message.answered`, `.rejected`, `.failed` with
`provider`, the reason and `error_code`; counters for the same and a timer from receipt to first text. No message
text, sender email or provider payload is logged.

## Not in this increment

- A webhook transport (needed for a store app) and Feishu.
- A second provider.
- Actions, personal connectors, files and images in a conversation.
- Choosing the agent a connection answers with; automatic answers in a group without a mention.
- Reading in a group what every member of it may read (Microsoft's rule); see rule 1.
- A budget for the bot alone; the bot's account in an ordinary Group.
- An agent the administrator chooses for the bot, as Onyx sets one a channel; the bot answers with the Tenant's
  default agent.
- Feeding a second message into the turn that is still running.
- Continuing a group conversation on the web: Onyx copies the bot's session into a new one owned by the person
  who clicks, which then runs under their access; a direct message's session already opens and continues on
  the web, as Dust's "Continue on Dust" does.
- The consent that lends the bot a personal connection in a group (above).
- An account binding for a platform that gives no email (Telegram, Zalo): a person links their account once from
  MemoryOS. `channel_identities` is the table it will write.
- The earlier messages of a group thread as context for a question asked in it.
- Several replicas of the API.

## Documents

Delivered with the code: `docs/specs/channel.md`, `docs/tests/channel.md`, `docs/runbooks/lark-bot.md` (console
steps, scopes, the secret file), the identity, chat and usage specs for service accounts and the session's surface,
`ARCHITECTURE.md` for the capability, and an ADR for the `channel` capability once implementation has started.
