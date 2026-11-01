# MEM-285 plan

One pull request after this design: the rule, its storage, the API and the switch.

## Core

- [x] `ChannelFeatures.groupMembers` (WhatsApp true; Lark and Zalo false), and `groupsNeedPermission` on the features.
- [x] Migration: `channel_groups.allowed_at`, null for every existing group.
- [x] `JdbcChannelGroupRepository`: `allowed`, `allow`; `ChannelGroup.allowedAt`; waiting groups listed first.
- [x] `ChannelInboundService`: a group that needs permission and has none is recorded without a last message and
  refused as `GROUP_NOT_ALLOWED` with `ChannelNotice.GROUP_NOT_ALLOWED`.
- [x] `ChannelConnectionService.allowGroup`: `CHANNELS_MANAGE`, `CHANNEL_INVALID` for a provider that needs none,
  audited.
- [x] Tests: an unallowed Zalo group is refused, recorded and told; once allowed it is answered; withdrawn, refused
  again; an unlinked sender is still told to link; Lark and WhatsApp groups are unaffected; allowing takes the
  capability and refuses a Lark group; forgetting drops the permission.

## API and web

- [x] `PUT …/groups/{groupId}/permission`, `permission` on `ChannelGroupResponse`; `openapi.yml`, Hey API client.
- [x] *Cho phép trả lời* switch on a waiting or allowed group; i18n; component test.

## Docs

- [x] Channel spec and test matrix; MEM-263 design's "a stranger gets nothing" note; roadmap.

## Acceptance on staging

Closed by the owner on 2026-10-28 without this run: the boxes below that are not ticked were not exercised on staging.

- [ ] The staging Zalo group is refused with the notice and listed as waiting; allowed, it answers; withdrawn, it is
  refused. Needs the owner to post in the group.
