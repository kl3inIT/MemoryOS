# MEM-285: a group of a provider that cannot list its people answers only once allowed

[MEM-285](https://linear.app/memory-os/issue/MEM-285). Design agreed with the owner on 2026-10-20.

## Today

A question in a group chat runs as the bot's `SERVICE` account, which reads what every member of the Tenant reads, and
the answer is posted for everyone in the group ([identity spec](../../../specs/identity.md)). The capability refuses a
group when someone outside the Tenant may read it:

- **Lark** names each sender's organisation and tells whether a group has outsiders: `EXTERNAL_GROUP`.
- **WhatsApp** lists a group's people (`ChannelChat.participants`, MEM-283): the group is answered only when every one of
  them linked an account to an active member, else `UNLINKED_IN_GROUP`.
- **Zalo** does neither: `ZaloChannelAdapter.chat` returns `ChannelChat.UNNAMED`, which says "no outsiders". A linked
  member can add the bot to a group with people outside the company, ask, and they read an answer built from internal
  documents. Checked in the code on 2026-10-20 (`ChannelInboundService.refusal`); the group is recorded and answered on
  its first question, with no administrator in the loop.

## How the references do it

Read on 2026-10-20 from source and official docs; quotes in the session's research notes.

| Product | Which groups it answers in | Whose permissions | Outsiders | Administrator allows each group |
| --- | --- | --- | --- | --- |
| **Onyx** Slack | Any channel it is in, on a mention | Channel posts search public documents only (`handle_regular_answer.py`: "answer based on public documents ONLY as to not leak information") | No check | Optional per-channel config |
| **Onyx** Discord | Only channels an administrator enabled ("channel configs are disabled by default") | One service identity | — | Yes |
| **Glean** Slack | All, added-only or a custom list | The asker's, privately; Public Mode only "content that anyone in the organization can search for" | "Glean will not respond in channels that include external parties" | Yes, a custom channel list |
| **Dust** Slack | Any channel, on a mention | The asker's, read by the channel | Guests and strangers refused unless their domain is listed | Not found |
| **OpenClaw** | Only listed group IDs: "Default is `groupPolicy: "allowlist"`; if your group allowlist is empty, group messages are blocked"; its personal-Zalo channel defaults to the allowlist too | The agent's own | Left to the operator: "decide by membership" | Yes, by group ID |
| **Microsoft 365 Copilot** | Any chat, on a mention | The asker's; shared at once only when "all users in the chat have access to the knowledge sources", else a private preview the asker approves | — | — |

MemoryOS already does what Glean does where the provider can tell (Lark) and checks every person where it lists them
(WhatsApp). For a provider that tells neither, OpenClaw's rule is the one that fits: the operator lists the groups the
bot may answer in, closed by default. Onyx's Discord bot does the same with channels off until enabled.

## Decisions

1. **A provider declares whether `chat` lists a group's people** (`ChannelFeatures.groupMembers`): WhatsApp does; Lark
   names organisations instead; Zalo does neither. A group needs an administrator's permission exactly when its
   provider has no organisations and does not list members, so the rule follows the provider's abilities, not its
   name, and a later provider of the same kind is closed by default.
2. **Such a group is not answered until an administrator allows it** (OpenClaw `groupPolicy: "allowlist"`, Onyx
   Discord). An addressed question from a linked member in a group not yet allowed is refused as `GROUP_NOT_ALLOWED`
   with a notice in the group, "Nhóm này chưa được quản trị viên cho phép dùng trợ lý.", so the asker knows why and
   nothing else is said. The check runs after the sender checks, so an unlinked sender is still only told to link.
3. **The group still shows on the bot's page.** It is recorded when it is refused, with no last message (it was not
   answered), so the administrator finds it under *Theo nhóm chat* (MEM-273) and allows it there; no page is added.
   Allowing is a switch, *Cho phép trả lời*, that saves on change, shown only for groups that need it. Groups waiting
   for permission are listed first.
4. **Permission is kept on the group** (`channel_groups.allowed_at`, null until allowed). Forgetting a group drops it,
   so a group that comes back waits again; turning the switch off refuses the group's next question. Allowing and
   withdrawing take `CHANNELS_MANAGE` and are audited (`group-allowed`, `group-disallowed`), like a group's label.
5. **Existing Zalo groups wait too.** The migration allows none of them: they were admitted by nobody, which is the
   hole. An administrator allows each one once.
6. **The API** adds `PUT /api/channels/{channelId}/groups/{groupId}/permission` (`allowChannelGroup`, body
   `{"allowed": boolean}`) and `permission` on `ChannelGroupResponse`: `NOT_NEEDED`, `ALLOWED` or `WAITING`. A group
   whose provider needs no permission refuses the call (`CHANNEL_INVALID`).

## Not in scope

- Telling the administrator when a new group waits (a notification); the bot's page lists it.
- Showing who is in a Zalo group: Zalo's Bot API gives no member list.
- Changing what a group answer may read (Onyx's public-only or Copilot's preview); the bot's account already reads only
  what every member reads.

## Risks

- **A Zalo group in use stops answering after the deploy** until it is allowed. Staging has one; the release note says
  so, and the group's next question tells its members to ask an administrator.
