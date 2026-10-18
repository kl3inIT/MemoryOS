# MEM-273 One bot, one assistant per group

A Tenant usually runs one Lark bot and one Zalo bot, because adding a bot means registering an app, asking for its
scopes and having it approved. Since [#731](https://github.com/kl3inIT/MemoryOS/pull/731) a bot answers with one
assistant everywhere it sits: bound to "Kế toán thuế", it answers the HR group and every direct message with
"Kế toán thuế" too. This design keeps the bot's assistant as its default and lets an administrator give each group the
bot is in an assistant of its own. It extends the [assistant editor design](design.md) (decisions 16–18, which put
per-group routing out of that design) and is plan step 5.

The owner chose this direction on 2026-10-17 after comparing the references below.

## Today

- `chat_channel_binding` (V183, Chat's) holds one assistant a bot; no row means the built-in assistant
  (`docs/specs/channel.md`, "The bot's assistant"). `ChatChannelBindings` reads and writes it; the channel calls it
  after authorising (`ChannelConnectionService.bindPersona`).
- A conversation held in a channel records its bot (`chat_session.channel_connection_id`) and may use the bot's
  assistant only while the bot is still bound to it (`JdbcChatRepository.CHANNEL_OR_USES`). `ChannelTurns.continues`
  starts a conversation over when the bot now answers with another assistant.
- Conversations are kept in `channel_conversations` (V158) by bot, provider chat and user. A group has one
  conversation for every member (`external_user_id` `@group`, `ChannelInboundService.GROUP`), continued for four
  hours; a direct message has one a person, continued for one hour. A group turn runs as the bot's service account,
  which reads what every member may read.
- Both adapters already tell a group from a direct message and carry the provider's chat id (`ChannelMessage.chatId`,
  `Kind.GROUP`): Lark from `chat_type` (`LarkChannelAdapter.java:163`), Zalo from `chat.chat_type`
  (`ZaloUpdates.java:51`). Lark already reads each group with `im/v1/chat get` to refuse external groups
  (`LarkChannelAdapter.externalChat`, cached an hour); that answer also carries the group's name. Zalo pushes only
  the group messages that name the bot and gives no group name.
- The assistant's Kênh tab binds, moves and removes bots ("Thêm kênh", "Gỡ"); the bot page links to the assistant.

## How the references do it

| | Unit an assistant is chosen for | Default | Where it is set |
| --- | --- | --- | --- |
| Onyx (Slack) | A Slack channel, by name, in a `SlackChannelConfig` with `persona_id` (`backend/onyx/db/models.py:4471`) | One `is_default` config a bot, unique (`uq_slack_channel_config_slack_bot_id_default`) | The bot's admin page: "Edit default config" and a table Channel · Assistant · Document Sets (`web/src/app/admin/bots/[bot-id]/SlackChannelConfigsTable.tsx`) |
| OpenClaw | A binding that matches an account (bot) or one peer: a direct message or a group (`docs/concepts/agent-bindings.md`, "Match a specific conversation") | The configured default owner when no binding matches | Configuration |
| LobeHub | One bot an agent: credentials entered on the agent's Channel tab (`src/features/AgentChannel/PlatformDetail/formState.ts`) | — | The agent page |

The two products built for teams on shared bots, Onyx and OpenClaw, choose the assistant per group under one bot with
a bot-wide default. LobeHub's one-bot-per-agent model is what #731 shipped; it fits only when bots are cheap.

## Decisions

1. **The bot keeps one default assistant; a group may override it.** `chat_channel_binding` stays the bot's default.
   A new Chat table `chat_channel_group_binding` (`tenant_id`, `connection_id`, `external_chat_id`, `persona_id`,
   `bound_at`), primary key (`connection_id`, `external_chat_id`), foreign key to `persona (tenant_id, id)`
   `ON DELETE CASCADE`, index on (`tenant_id`, `persona_id`). Chat owns it for the same reason it owns the default:
   it decides per turn whether a conversation may still use an assistant, and Chat may not read the channel's
   tables. Removing a bot removes its group overrides through `ChatChannelBindings`, as it removes the default today.
   Like Onyx's one default per bot, a group has at most one override.
2. **Overrides are for groups only.** A direct message answers with the bot's default. OpenClaw can bind one direct
   peer, but a person-by-person table is not something an administrator of a shared bot maintains; a person who wants
   another assistant opens it on the web.
3. **The groups an administrator can choose are the groups the bot has answered in.** A channel table
   `channel_groups` (`tenant_id`, `connection_id`, `external_chat_id`, `name`, `label`, `first_seen_at`,
   `last_message_at`), primary key (`connection_id`, `external_chat_id`), foreign key to `channel_connections`
   `ON DELETE CASCADE`. `ChannelInboundService` records a group when it accepts an addressed group message that is not
   external, and moves `last_message_at` on each one. `name` is the provider's: Lark's comes from the `chat get` the
   external check already makes (the adapter port's `externalChat` becomes `chat(connection, chatId)` returning
   whether it is external and its name, cached as today); Zalo gives none. `label` is the administrator's own name for
   a group and is shown before `name`; a Zalo group without a label shows "Nhóm Zalo" and when it last asked. A Zalo
   group the bot left stays until the administrator forgets it; forgetting removes its override too. Lark's groups
   are known before anyone asks there (decision 9).
4. **Resolution at turn time:** the group's override, else the bot's default, else the built-in assistant.
   `ChatSessionService.channelPersona(actor, connection, groupChatId)` answers it; `createInChannel` takes the group
   and records it on the session (`chat_session.channel_chat_id`, null for a direct message).
5. **The #731 security rule follows the resolution.** `CHANNEL_OR_USES` admits a channel conversation's assistant only
   while that assistant is still what its bot and group resolve to: the group's override when one exists, else the
   bot's default. Changing a group's assistant, removing its override or forgetting the group ends access at once, and
   `ChannelTurns.continues` compares with the resolved assistant, so the group's conversation starts over, as moving a
   bot does now. Documents stay those of the bot's service account in a group.
6. **Permissions mirror #731.** Setting a group's assistant takes `CHANNELS_MANAGE` and edit rights on the assistant
   it goes to and on the override it replaces while that one exists (`CHANNEL_ASSISTANT_NOT_EDITABLE`); removing an
   override takes edit rights on the assistant it leaves. Naming or forgetting a group takes `CHANNELS_MANAGE`. Each
   change is audited as `channel.update` with `change` `group-assistant`, `group-assistant-removed`, `group-label` or
   `group-forgotten`.
7. **The bot page is where routing is set,** as in Onyx. `/admin/channels/{id}` gains:
   - **Trợ lý mặc định:** an assistant picker in place of today's link, writing `PUT /api/channels/{id}/persona`.
   - **Theo nhóm chat:** the bot's groups (label or name, last question), each with an assistant picker whose first option
     is "Theo mặc định" (no override), a rename and "Quên nhóm" in its menu. The heading says "nhóm chat" and each
     row carries the platform's mark and "Nhóm Lark" or "Nhóm Zalo", because MemoryOS's own Groups (people, for
     sharing) often carry the same names, such as "Phòng Kế toán" (owner review, 2026-10-17). The list reuses `AppRow` and the
     assistant picker of the default. An empty list shows nothing but the section heading; Zalo groups appear once
     someone names the bot there.
8. **The assistant's Kênh tab only shows where the assistant answers:** the bots it is the default of and the groups
   that override to it, each linking to its bot page. "Thêm kênh" and "Gỡ" go; this also removes the floating "Gỡ"
   seen on staging. Routing is an administrator's task on a shared bot, and keeping two places that write it would
   need the move confirmation twice.
9. **Save and Cancel show only on the Cấu hình tab.** Kênh and Chia sẻ save on change; on those tabs the header keeps
   "Có thay đổi chưa lưu" while the configuration has unsaved changes, without the buttons (staging finding,
   2026-10-17).

## API

| Operation | What it does |
| --- | --- |
| `GET /api/channels/{id}/groups` | The bot's groups: id, label, name, first and last message, override assistant (`id`, `name`) |
| `PUT /api/channels/{id}/groups/{groupId}/persona` | Sets a group's assistant (`personaId`) or removes the override (`null`) |
| `PUT /api/channels/{id}/groups/{groupId}/label` | Names a group (`label`, up to 100 characters, blank clears it) |
| `DELETE /api/channels/{id}/groups/{groupId}` | Forgets a group and its override; `204` |
| `GET /api/chat/personas/{id}/channels` | Gains each bot's groups that override to the assistant |

`groupId` is the provider's chat id, URL-encoded. One migration (the next free version when it is pushed) adds
`channel_groups`, `chat_channel_group_binding` and `chat_session.channel_chat_id`.

## Step 5d: Lark groups known on joining

Owner's call (2026-10-18): a Lark bot's groups show on its page as soon as the bot is in them; a Zalo group still
shows after its first @-mention, as Zalo's Bot API has no list of a bot's groups and no event for being added.

9. **Lark tells, Lark lists.** oapi-sdk-java 2.8.5 gives both (verified in `tmp/refs/oapi-sdk-java`): the
   `im.chat.member.bot.added_v1` event, which `LarkChannel` already subscribes and emits as `botAdded` with the
   chat's id, its name and the raw event's `external`; and `im().v1().chat().list` (`GET /open-apis/im/v1/chats`),
   which with a tenant token lists the groups the bot is in, never a direct chat, 100 a page, each with `external`
   and `chat_status`. The list needs `im:chat:readonly`, which the bot already has for the external check.
   - The adapter port gains `ChannelReceiver.joined(chatId, name)` beside the message callback and
     `ChannelAdapter.groups(connection)`, the whole list or nothing (empty for Zalo). A group with outsiders or not
     `normal` is left out, as the bot answers in neither.
   - `ChannelGroupRoster` records a joined group at once (`channel_groups.last_message_at` null: nobody has asked
     yet), and makes the list the bot's groups, read when the bot connects and every 10 minutes: groups missing from
     it are ones the bot left and are removed with their own assistant, so coming back starts on the bot's default,
     as a new group does. Lark's `im.chat.member.bot.deleted_v1` and `im.chat.disbanded_v1` would remove a group
     sooner, but `LarkChannel` builds its own event dispatcher without them, so the listing removes it within ten
     minutes; until then the bot cannot answer there anyway. A list read in part, past 50 pages or refused changes
     nothing.
   - Onyx's Slack bot asks the administrator to type a channel's name (`SlackChannelConfigFormFields.tsx:205`) and
     lists no channels; MemoryOS lists them because Lark gives the list and a chat id is not something an
     administrator can type.
   - The page lists groups asked in, most recent first, then those nobody has asked in ("chưa có ai hỏi"). A Lark
     group has no "Quên nhóm": the list is Lark's, and a forgotten group would come back at the next read. A Zalo
     group keeps it.
   - The add dialog and the runbook name the new event; a bot whose app lacks it still gains its groups at the next
     listing.

## Not in scope

- **WhatsApp.** It waits for the owner's choice between the official Cloud API and a WhatsApp Web adapter. The Cloud
  API's groups need an Official Business Account, hold at most eight participants and are joined by invite link
  (third-party summary: https://www.unipile.com/whatsapp-group-api/), which limits what per-group routing gives.
- Per-person routing of direct messages; per-group document sets or tools (Onyx's config carries document sets).
- GoClaw's per-group behaviour on one agent (`internal/config/config_channels.go:115`, `TelegramGroupConfig`: extra
  system prompt, tool and skill lists, enabled, require mention, allow list, quota). Switching the bot off in one
  group and requiring a mention there are candidates for later.

## Risks

- **A group's answers change when its assistant changes.** The group's conversation starts over, so two assistants'
  answers never share one conversation; the picker change is audited.
- **A group the bot left still shows.** It is harmless and can be forgotten; `last_message_at` shows how stale it is.
- **The resolution must stay one query.** The access rule and `channelPersona` read the same two tables; a test
  covers each branch (override, default, built-in) and each way access ends.
