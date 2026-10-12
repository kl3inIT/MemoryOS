# Lark connector setup

How an organization connects its Lark tenant. The contract is in the [Connector specification](../specs/connector.md#lark-credential-authority); this page is what an administrator and an operator do.

## On the server, once

The API and the worker need the key that seals App Secrets, as a mounted file, and the name of that key:

```bash
sudo sh -c 'umask 077; openssl rand -base64 32 > /apps/memoryos/secrets/encryption/lark-credential-key.txt'
sudo sh -c 'echo "MEMORYOS_LARK_CREDENTIAL_KEY_VERSION=<environment>-v1" >> /apps/memoryos/.env.<environment>'
```

A deployment without either stops before it changes a container. Keep the key: App Secrets stored under it cannot be read with another, and a lost key means entering every App Secret again.

## In the Lark Developer Console

1. Create a custom app for the organization and enable its bot.
2. Under Permissions & Scopes add these scopes **for the tenant token (app identity)**. A scope added only for the user token type looks granted and is still refused; MemoryOS lists it as missing on the credential.

   | Scope | Used for |
   | --- | --- |
   | `wiki:wiki:readonly` | Wiki spaces and their pages |
   | `drive:drive:readonly` | Drive folders and file downloads |
   | `docx:document:readonly` | Documents |
   | `sheets:spreadsheet:readonly` | Spreadsheets |
   | `docs:permission.member:retrieve` | Who a document is shared with |
   | `im:chat:readonly` | Members of a chat a document is shared with |
   | `contact:user.base:readonly` | Members of a department a document is shared with |
   | `contact:user.email:readonly` | Matching Lark people to MemoryOS accounts by email |

   The last four are needed only for a Source read by Lark's own sharing.
3. Publish a version with **availability set to all members**. The app reads only the departments its availability covers: with the default, it sees its creator alone and no department.
4. Copy the App ID and the App Secret from Credentials & Basic Info.

## In Lark, for each Source

- Add the app's bot as a member of every Wiki space to synchronize (space settings › members). A space administrator does this; the bot cannot add itself.
- Share every Drive folder to synchronize with the bot, and copy the folder's link from the address bar.
- For a Source read by Lark's sharing, add the bot to every chat documents are shared with. Lark does not show an app the members of a chat it is not in, so such a chat admits nobody.

## In MemoryOS

Sources › Add source › Lark: create the credential (it is verified with Lark before it is stored), tick the Wiki spaces, paste the folder links, choose the access and create. The first run starts once Lark has confirmed the app can read every root.

## What to expect

- **Sharing.** With "Sync permissions from source", a document is found by the people, chats, departments and spaces it is shared with. A link open to the organization grants nobody, and that is Lark's default for a new document: a document nobody was added to is found by its owner alone. Choose Public or Private for a Source everyone or chosen groups should read.
- **Matching.** A Lark person is the MemoryOS account whose verified login email equals their Lark email. Someone whose two emails differ reads nothing through Lark's sharing.
- **Delay.** A person added to a document reads it after the Source's next run; a change of chat, department or space membership is read within an hour.
- **Quota.** Every run lists the whole scope, and a Source read by Lark's sharing spends two more calls per object. A Starter tenant has 10,000 API calls a month ([Lark plans](https://www.larksuite.com/plans)): choose the interval accordingly. A throttled run waits and resumes; it does not fail the Source.
- **Not read.** Base, mind maps, slides, old-format documents and shortcuts are listed and skipped.

## Failures

| Code | What to do |
| --- | --- |
| `SOURCE_LARK_NOT_CONFIGURED` | The server has no Lark key: see "On the server" |
| `SOURCE_LARK_CREDENTIAL_REJECTED`, `SOURCE_LARK_AUTHENTICATION` | Copy the App ID and App Secret again; replace the secret on the credential |
| `SOURCE_LARK_SCOPE_MISSING` | Grant the scopes the credential lists, for the tenant token, and publish the app |
| `SOURCE_LARK_AUTHORIZATION`, `SOURCE_LARK_NOT_FOUND` | Add the bot to the space or share the folder with it again |
| `SOURCE_LARK_QUOTA`, `SOURCE_LARK_UNAVAILABLE` | Nothing: the run is retried |
