# Channel

The Tenant's chat bots on messaging platforms, the Lark bot first. Design and status:
[MEM-246](../increments/active/mem-246-lark-bot/design.md). What a bot receives and how a question becomes a Chat
turn are not built yet; this page describes what exists.

## Connections

A Tenant may have several bots; `channel_connections` holds one row a bot: its provider (`LARK`), a name the
administrator gives it, the region of its app (`LARK` or `FEISHU`), the app's id, the app's secret as an AES-GCM
envelope, the language of the bot's own notices (`vi` or `en`), whether it is switched on, the service account it
owns and a revision. One provider app serves one bot across every Tenant.

`ChannelConnectionService` lists, reads, adds, switches on or off, replaces the secret of and removes a bot. Every
call requires `CHANNELS_MANAGE`, an assignable administrative capability that administrators hold; a write takes
the Tenant's exclusive authority lock and checks the revision it was given (`CHANNEL_CHANGED` when it moved on).

- **Adding** validates the name (1 to 100 characters), the app id (a Lark app id starts with `cli_`), the secret (1 to
  256 characters) and the language, refuses an app another bot holds (`CHANNEL_APP_IN_USE`), and creates the bot's
  service account ([identity](identity.md#account-classification-and-group-authority)) named after the bot, in the
  same transaction. A new bot is switched off.
- **Removing** deletes the row and retires the service account, so the conversations and costs of the bot keep its
  name.
- The secret is encrypted with `memoryos.channel.credential-encryption-key` and `credential-key-version`, a key of
  the channels' own, apart from the connectors'; the credential kind in the envelope's authenticated data is
  `CHANNEL_<PROVIDER>`. Without the key every bot call fails with `CHANNEL_NOT_CONFIGURED` and nothing else is
  affected. No view carries the secret.
- Each change is audited as `channel.create`, `channel.update` (`change`: `enabled`, `disabled`, `secret`) or
  `channel.delete`.

No HTTP surface exposes this yet; the administrator's API comes with the Lark adapter.
