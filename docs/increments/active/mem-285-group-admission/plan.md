# MEM-285 plan

One pull request after this design: the rule, its storage, the API and the switch.

## Core

- [ ] `ChannelFeatures.groupMembers` (WhatsApp true; Lark and Zalo false), and `needsPermission` on the features.
- [ ] Migration: `channel_groups.allowed_at`, null for every existing group.
- [ ] `JdbcChannelGroupRepository`: `allowed`, `allow`; `ChannelGroup.allowedAt`; waiting groups listed first.
- [ ] `ChannelInboundService`: a group that needs permission and has none is recorded without a last message and
  refused as `GROUP_NOT_ALLOWED` with `ChannelNotice.GROUP_NOT_ALLOWED`.
- [ ] `ChannelConnectionService.allowGroup`: `CHANNELS_MANAGE`, `CHANNEL_INVALID` for a provider that needs none,
  audited.
- [ ] Tests: an unallowed Zalo group is refused, recorded and told; once allowed it is answered; withdrawn, refused
  again; an unlinked sender is still told to link; Lark and WhatsApp groups are unaffected; allowing takes the
  capability and refuses a Lark group; forgetting drops the permission.

## API and web

- [ ] `PUT …/groups/{groupId}/permission`, `permission` on `ChannelGroupResponse`; `openapi.yml`, Hey API client.
- [ ] *Cho phép trả lời* switch on a waiting or allowed group; i18n; component test.

## Docs

- [ ] Channel spec and test matrix; MEM-263 design's "a stranger gets nothing" note; roadmap.

## Acceptance on staging

- [ ] The staging Zalo group is refused with the notice and listed as waiting; allowed, it answers; withdrawn, it is
  refused. Needs the owner to post in the group.
