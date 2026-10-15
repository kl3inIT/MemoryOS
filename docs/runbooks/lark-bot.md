# Lark bot

How the chat bots' process (`memoryos-channels`, [MEM-246](../increments/active/mem-246-lark-bot/design.md)) is
provisioned on a host, and how an administrator connects a Lark bot. It runs on staging only until the bot is
accepted; production's Compose files do not define it. What the bot does is in the
[channel spec](../specs/channel.md).

## Provision a staging host

Once, as root, before the first release that carries the process is deployed. The key is made on the host and never
leaves it:

```sh
umask 077
mkdir -p /apps/memoryos/secrets/encryption
head -c 32 /dev/urandom | base64 > /apps/memoryos/secrets/encryption/channel-credential-key.txt
grep -q '^MEMORYOS_CHANNEL_CREDENTIAL_KEY_VERSION=' /apps/memoryos/.env.staging \
  || echo 'MEMORYOS_CHANNEL_CREDENTIAL_KEY_VERSION=staging-v1' >> /apps/memoryos/.env.staging
```

Without them a deployment refuses before it reserves anything: Compose requires the version and `deploy.sh` checks
that every secret file exists. Replacing the key makes every stored bot secret unreadable; enter each bot's secret
again after a new version.

Optional settings in the same environment file:

| Variable | Default | What it does |
| --- | --- | --- |
| `MEMORYOS_CHANNEL_WEB_URL` | empty | The web address of this environment (for staging `https://staging.vadan.app`); a direct answer that ran as its sender links to its session there. Empty: no link |
| `MEMORYOS_CHANNELS_TURN_CONCURRENCY` | 24 | Turns the process runs at once. Keep it, plus the API's 80, under the model provider's limit (below) |
| `MEMORYOS_CHANNELS_DATABASE_POOL_SIZE` | 5 | The process's database pool, counted in the role's budget |

A change to the environment file takes effect at the next deployment.

## Create the Lark app

In the Lark Developer Console (`open.larksuite.com`, or `open.feishu.cn` for Feishu), as a member allowed to create
apps in the organisation. The "Kênh chat" add dialog shows the same steps:

1. Create a **custom app** and turn on the **Bot** feature. A custom app belongs to one organisation; a store app
   needs a webhook, which MemoryOS does not have.
2. Add the scopes:
   - `im:message.p2p_msg:readonly` and `im:message.group_at_msg:readonly`: direct messages, and group messages that
     name the bot (Lark delivers no other group message);
   - `im:message:send_as_bot`: replies and notices;
   - `im:message.reactions:write_only`: the reaction on a question that waits;
   - `cardkit:card:write`: the streamed answer card;
   - `contact:user.email:readonly`: the sender's e-mail, by which a direct message runs as its sender;
   - `im:chat:readonly`: whether a group has people from outside the organisation.
3. In **Events & callbacks**, receive events through the **long connection** and add `im.message.receive_v1`.
4. Create a version and publish it; an organisation administrator may have to approve it. Copy the App ID and the
   App Secret.

In the organisation's admin console, check the member field setting for e-mail: a member who may edit their own
address could take that of a MemoryOS account which belongs to nobody in Lark.

## Connect it in MemoryOS

An administrator with `CHANNELS_MANAGE` opens **Kênh chat** in the administration, under agents and tools (`/admin/channels`), and chooses
**Thêm bot Lark**: a name, the region, the App ID, the App Secret and the language of the bot's own notices. MemoryOS
asks Lark first; a refused secret or an app without a bot is not stored, and a scope Lark refuses is stored and
shown on the bot's page. The bot is added switched off; its switch saves on change.

The list shows each bot's state: **Connected**, connecting, off, a failure with its code, or **the bots' process is
not running** when no `memoryos-channels` has reported for 90 seconds. A secret changed in Lark is replaced from the
bot's menu, and deleting a bot retires its account and keeps its name on old conversations.

## Messages and restarts

- The process holds one long connection a bot and reopens a dropped one at the next 30-second pass. On staging
  (2026-10-15) a restart while idle had the bot connected again about 20 seconds later.
- Stopping the process closes the connections, then waits up to 30 seconds for answers in progress; a turn still
  running when the process dies ends when its lease lapses (3 minutes). A turn the API was running when the API
  restarted ended at once as interrupted, and its session took the next question.
- Lark gives an event to one open connection and pushes it again when it is not acknowledged within three seconds,
  after 15 seconds, 5 minutes, 1 hour and 6 hours. A message is claimed by its id, so a message pushed twice is
  answered once. Whether Lark keeps an event for a bot that has no connection open, and for how long, has not been
  observed; treat a message sent while the bot is down as possibly lost.
- A message older than 90 days is forgotten, so a reply to it starts a new conversation.

## The model provider's limit

Every turn of the API and of the bots calls the same provider. On staging, 9Router with `cx/gpt-6-luna` answered 160
requests at once and refused most of 256; going over its limit locks every account behind it for up to five
minutes, which stops all turns, not only the excess
([verification](../increments/active/mem-246-lark-bot/verification.md#the-providers-limit-2026-10-15)). Raise
`MEMORYOS_CHANNELS_TURN_CONCURRENCY` or the API's bound only while their sum stays under that limit, with room for
the provider's other users.

## What the process uses

- The API's Redis user and object-storage identity: a turn's rights are exactly those of the API's turns.
- Its own database pool, `MEMORYOS_CHANNELS_DATABASE_POOL_SIZE` (5). `deploy.sh` counts it in the role's budget:
  API 15, Worker 10, channels 5 and 10 spare fit the default limit of 40.
- A memory limit of `MEMORYOS_CHANNELS_MEMORY_LIMIT` (1536m) and one replica.
- Health on port 8082 inside the container (`/actuator/health/readiness`); no published port.

## Deploying

`deploy.sh` starts it after the API and the Worker, stops it with the writers before the backup and migration, counts
it in verification, and restores the previous release's container on rollback. A release without its image, or an
environment whose Compose files do not define it, simply has none.

The alert `MemoryOSChannelsTelemetryMissing` fires when its telemetry is absent for five minutes.
