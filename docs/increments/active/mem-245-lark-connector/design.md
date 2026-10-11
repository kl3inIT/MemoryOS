# Lark Suite connector

Status: **design accepted 2026-10-12; implementation in progress.** Linear:
[MEM-245](https://linear.app/memory-os/issue/MEM-245). It is the Lark Suite part of
[MEM-118](../mem-118-connector-adapters-lark/design.md#6-lark-suite) (which absorbed MEM-128), split out so the
connector standardization and the reader hierarchy can close on their own.

## Problem

Lark Suite is Tasco's everyday workspace. Its Wiki spaces and Drive folders hold the knowledge MemoryOS should answer
from, under the same rule as Google Drive: a reader finds a Lark document only when Lark lets them open it.

## Owner decisions (2026-10-11 and 2026-10-12)

1. Lark international (`open.larksuite.com`) first. The base URL is stored per credential, so Feishu can be added
   without a schema change.
2. One **custom app per Lark tenant**, authenticated with its App ID and App Secret (tenant access token), as
   SharePoint authenticates one Entra application. Per-person OAuth is later, only for personal space the app is not
   given.
3. The secret is encrypted in PostgreSQL per MemoryOS Tenant, never in deployment configuration, so a hosted
   multi-Tenant deployment needs no change.
4. Content: Wiki spaces, Drive folders, Docx, Sheets and uploaded files (PDF, Office). Out: Messenger, Base,
   Calendar, Minutes, Slides, Mindnote.
5. Access modes PUBLIC, PRIVATE and SYNC; SYNC is the default, as for Google Drive.
6. A document shared "anyone in the organisation with the link" (`tenant_readable` / `tenant_editable`) grants
   **nothing** in SYNC, as Google's link-only `domain` permission grants nothing
   ([connector contract](../../../specs/connector.md#source-access-modes)) and as spring-agent's `FeishuDriveAccess`
   refuses it. `anyone_readable` grants every active Tenant member.
7. Collaborators that are group chats and departments are expanded in the first release.
8. The Lark bot (questions asked inside Lark) is a separate increment with its own ADR; it will reuse the `LARK_APP`
   credential.
9. The work follows the order foundation → credential → selection → sync → permissions → web; Auto Sync is not
   merged before it is verified on a real tenant.

## Evidence from the test tenant (2026-10-12)

A Lark international tenant with app `cli_aa4102bd7238de15` and a Wiki space, a Drive folder and a private document
created through the API. Recorded responses are kept for adapter tests (plan step 1).

| Question | Answer |
| --- | --- |
| What does a tenant token see? | Only what is shared with the app: no space and no file before sharing; one space and its ten nodes after the bot was added as a space member |
| How is the app given a space? | `POST wiki/v2/spaces/{id}/members` with `member_type=openid` and the bot's `open_id` (`bot/v3/info`) succeeds; no group workaround is needed |
| Who can create a space? | Only a person: `POST wiki/v2/spaces` with a tenant token is refused before scope checks |
| Can the app list folders shared with it? | No. `drive/v1/files` without `folder_token` lists the app's own root; a shared folder is read by its token, so Drive roots are entered as links |
| Does link sharing let the app in? | Yes. New documents in the tenant defaulted to `tenant_readable`, and the app read a document nobody shared with it until it was set to `closed` (`1770032 forBidden` after) |
| What does a collaborator list return? | `member_type` (`openid`, `appid`, `openchat`, `opendepartmentid`, `wikispaceid`, …), `member_id`, `perm`, `type`; no email |
| How are Wiki pages shared? | A page's list carries `wikispaceid=<space>` entries (`edit`, `view`) beside named collaborators |
| Where is the link setting? | `GET drive/v1/permissions/{token}/public?type=…` (`link_share_entity`, `external_access`). The v2 endpoint answered `1063002` |
| Are emails readable? | Only with `contact:user.email:readonly`; without it `contact/v3/users` returns `open_id`/`union_id` only |
| How is Docx read? | `docx/v1/documents/{id}/blocks` keeps structure (table blocks 31/32); `raw_content` is flat text |
| What failures look like | HTTP 400 with a JSON `code` (`99991672` missing scope names the scopes; `99991663` invalid token; `99991679` user scope missing) |
| Scope pitfall | A scope added only for the *user* token type looks granted in the console and is still refused to the tenant token |

Starter tenants have 10,000 OpenAPI calls a month ([Lark plans](https://www.larksuite.com/plans)); quota and
rate-limit refusals must stop a run with a clear code, not retry in a loop.

## Design

### Placement

Following ADR 0016 and ADR 0019, as SharePoint:

| Module | Holds |
| --- | --- |
| `core` `io.memoryos.connector` (root) | `LarkGateway` port and its records, `LarkProviderException`, `LarkException` (business codes `SOURCE_LARK_*`), `LarkCredentialService` and `LarkSourceService` ports, `SourceType.LARK`, `SourceInputFormat.LARK_DOCX` and `LARK_SHEET` |
| `core` `io.memoryos.connector.lark` | Credential and Source services, `LarkSelectionAdapter`, `LarkSyncAdapter`, the directory synchronizer, `persistence/` |
| `sources` `io.memoryos.connector.adapter.lark` | `RestLarkGateway` on the official SDK (`com.larksuite.oapi:oapi-sdk`), `LarkTransport`, properties, metrics, `LarkDocxSourceContentExtractor`, `LarkSheetSourceContentExtractor` |
| `api` `io.memoryos.api.source` | `LarkCredentialController`, `LarkSourceController`, `contract/` records |
| `web` `features/sources/lark` | Credential section, setup page, panel |

No Gradle module is added; the SDK joins `sources` beside the Google and Microsoft SDKs.

### Transport

The SDK accepts an `IHttpTransport` (`RawRequest` → `RawResponse`). `LarkTransport` implements it on the JDK
`HttpClient` with the four transport rules of [conventions](../../../conventions.md#change-design):

- no redirect (`Redirect.NEVER`) and no retry. The SDK's only retry is one repeat of a token request, which stays
  bounded by the per-request deadline;
- a deadline per exchange from send to last byte;
- a successful body is refused above its declared or counted bound;
- a failed answer is read up to 8 KiB, because Lark names the reason in a JSON `code`, and is classified by that code;
  its text is never logged or passed on.

`logReqAtDebug` stays off, the SDK's token cache is per gateway session, and the base URL comes from the credential.
The SDK's WebSocket client (`com.lark.oapi.ws`) is the only user of its shaded protobuf, whose `UnsafeUtil` warns on
JDK 24 and later; the REST services never load it. The bot increment re-checks this when the JDK is upgraded.

### Credential

`credentials.credential_kind = 'LARK_APP'`, statuses `ACTIVE` and `NEEDS_UPDATE`, owned like SharePoint credentials.
`lark_credentials` holds the App ID, the region (`LARK` now; `FEISHU` reserved), the encrypted App Secret envelope
(`CredentialCipher`, purpose `app-secret`, key `memoryos.lark.credential-encryption-key`), the Lark tenant key and the
bot's `open_id` recorded on verification. Creating or testing a credential fetches a tenant token, reads `bot/v3/info`
and probes the read scopes; a refused scope is reported by name so the setup can say which tenant-token scope is
missing. Replacing the secret keeps the App ID.

### Selection

SPECIFIC only. A root is a Wiki space the app is a member of (picked from `wiki/v2/spaces`) or a Drive folder link
(`/drive/folder/{token}`). Verification uses `source_selection_operations` with `lark_selection_details` and resolves
each root once through the shared selection workload.

### Synchronization

`LarkSyncAdapter` walks each root breadth first with a checkpoint (`lark_sync_runs`), like a SharePoint folder root:

- Wiki: `wiki/v2/spaces/{id}/nodes` by parent; each node's object (`obj_type`, `obj_token`) is the item;
- Drive: `drive/v1/files?folder_token=` pages, descending into folders.

| Object | Acquired as |
| --- | --- |
| `docx` | `LARK_DOCX` snapshot: the block list (`docx/v1/documents/{id}/blocks`), rendered to Markdown by `LarkDocxSourceContentExtractor` (headings, lists, code, quotes, tables 31/32, embedded sheets as tables) |
| `sheet` | `LARK_SHEET` snapshot: each sheet's values within bounds, rendered as tables |
| `file` | `BINARY` download into the existing extraction |
| anything else (`bitable`, `mindnote`, `slides`, `doc`, shortcuts) | skipped with a counted reason, never indexed |

The version is the object's `modified_time` (and revision where returned). Items not seen by a complete listing are
removed, as SharePoint prunes. The link a reader opens is the node or file URL Lark returns, only ever on the
credential's Lark host.

### Permissions (SYNC)

Two parts, as Google Drive keeps file permissions and group membership apart, so a membership change needs no
re-index.

**Per file**, on every run (as Google reads permissions on every run): the collaborator list and the link setting are
stored in `lark_acl_snapshots` with an observation revision; a change publishes `SourceAclChanged`, whose listener
rewrites the chunks' access in place.

| Lark | Index grant token |
| --- | --- |
| `openid` collaborator (a person) | `lark_user:<open_id>` |
| `openchat` collaborator | `lark_chat:<chat_id>` |
| `opendepartmentid` collaborator | `lark_department:<department_id>` |
| `wikispaceid` entry | `lark_space:<space_id>` |
| `appid`, unknown member types | nothing |
| link `anyone_readable` / `anyone_editable` | every active Tenant member |
| link `tenant_readable` / `tenant_editable`, `closed` | nothing |
| a list that could not be read completely | the last successful snapshot, as Google; none before the first success |

**Per credential**, a directory synchronized in generations like Google Groups (ADR 0013): the tenant's users
(`open_id` → email), and the members of every chat, department and space that some snapshot references. A reader's
tokens are derived from their verified login email (ADR 0011): their `open_id` under each active `LARK_APP`
credential of the Tenant, and the chats, departments and spaces whose active-generation members include it.

`SYNC_GRANTS` and `READER_TOKENS` in `JdbcSourceDocumentRepository` become a union of a Google part and a Lark part;
index-time access, the query filter and the post-query recheck keep sharing the one rule, so they cannot drift.

### Failures

`LarkProviderException.Failure`: `AUTHENTICATION` (`99991663`, `99991661`, app secret refused) → reconnect;
`AUTHORIZATION` (`99991672`, `1770032`, `131006`) → item or root failure with the missing scope recorded;
`NOT_FOUND`; `QUOTA` (HTTP 429, `99991400`) → retry after `Retry-After` or the rate-limit reset; `UNAVAILABLE`
(5xx, transport) → retry; `MALFORMED`. Codes surface as `SOURCE_LARK_<FAILURE>`.

## Not in this increment

The Lark bot, per-person OAuth, Feishu, Base/Messenger/Calendar content, the reader hierarchy view (MEM-118 step 6).

## References

Recorded probes and fixtures in plan step 1. Reference code read under `tmp/refs/` (not committed): Lark
`oapi-sdk-java` (transport and samples), spring-agent `integration-feishu` (`FeishuDriveAccess`, the collaborator
and chat checks; the refusal of link sharing), RAGFlow `feishu_wiki.go` and WeKnora `connector/feishu` (traversal,
block rendering, rate limits; neither syncs permissions), Onyx Slack connector and bot (permission sync by email,
the bot as its own process).
