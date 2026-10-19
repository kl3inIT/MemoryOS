# WhatsApp bot

Operating a WhatsApp bot on staging ([channel spec](../specs/channel.md#the-whatsapp-adapter),
[MEM-283](../increments/active/mem-283-whatsapp-bot/design.md)). The bot is a WhatsApp number linked to MemoryOS as a
companion device, through the whatsmeow bridge `memoryos-channels` starts.

## Before adding a bot

- Use a number dedicated to the bot, with WhatsApp (or WhatsApp Business) installed on a phone the administrator
  keeps. Never a person's own number: WhatsApp's terms forbid unofficial clients and a number can be banned.
- Give the account a name, a photo and a status, as a person's would.
- The bot only answers people who write to it first and have linked their MemoryOS account with a code; it never
  starts a conversation and sends no bulk messages. Keep it so: five to ten spam reports can ban a number.

## Linking

1. *Kênh chat › Thêm bot › WhatsApp*: the bot's name and its number in international form (`+84 …`).
2. Switch the bot on. Within a pass (30 seconds) its page shows a QR code, refreshed about every 20 seconds.
3. On the bot's phone: WhatsApp › *Linked devices* › *Link a device*, and scan the code. The device appears as
   "MemoryOS".
4. A code scanned by another number is refused (`CHANNEL_WHATSAPP_WRONG_NUMBER`), as is a number already linked to
   another bot (`CHANNEL_WHATSAPP_NUMBER_IN_USE`); scan with the bot's own phone, or remove the other bot first.

## When it stops

| Failure | What happened | What to do |
| --- | --- | --- |
| `CHANNEL_WHATSAPP_LOGGED_OUT` | The device was removed on the phone, or WhatsApp ended it | *Liên kết lại* on the bot's page, then scan the new code |
| `CHANNEL_WHATSAPP_BANNED` | WhatsApp banned the number for a while | Wait for the ban to end; check nothing sent unwanted messages; then *Liên kết lại* |
| `CHANNEL_WHATSAPP_REPLACED` | Another client opened the session | Check that no second deployment holds the bot; *Liên kết lại* |
| `CHANNEL_LEASE_LOST` | This process could not renew its hold on the bot | Usually the database; the bot reopens at the next pass by itself |
| `CHANNEL_PROVIDER_UNAVAILABLE` | The bridge could not start or WhatsApp could not be reached | `docker logs memoryos-channels` for `channel.whatsapp.bridge.*` events |

The bridge's own log is in the channels log as `channel.whatsapp.bridge.log`, with its module. Its sessions are the
`whatsmeow_*` tables and `channel_devices` in the schema `whatsapp`; never edit them by hand, and never copy them to
another deployment, which would open the same session twice.

## Removing a bot

Deleting it logs its device out and deletes its session; the phone's *Linked devices* list drops "MemoryOS". A bot
deleted while no process held it is logged out by the bridge within the hour.

## Updating whatsmeow

WhatsApp changes its protocol without notice; whatsmeow follows it. Dependabot opens a `gomod` pull request weekly.
When bots stop connecting with no failure of their own, or the log shows `channel.whatsapp.outdated`, update by hand:

```sh
cd memoryos-integration-whatsapp/bridge
go get go.mau.fi/whatsmeow@main && go mod tidy && go test ./...
```

Then open a pull request as for any dependency; the channels image is rebuilt with it.
