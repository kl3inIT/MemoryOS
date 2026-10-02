# MCP endpoint: Keycloak and routing

Operating procedure for the identity and routing side of the MemoryOS MCP endpoint
([MEM-114 design](../increments/active/mem-114-public-mcp-server/design.md)). Claude and ChatGPT reach `/mcp` on the
browser origin, sign the member in through Keycloak and receive a token whose audience is that URL.

## What has to be in place

| Piece | Where | Effect |
| --- | --- | --- |
| Keycloak 26.8 built with `cimd` and the `memoryos-client-id-metadata-document` executor | `infrastructure/keycloak/Dockerfile` | The realm advertises `client_id_metadata_document_supported`, so Claude and ChatGPT each identify themselves by a metadata document instead of registering a client per connection |
| Realm reconciliation with `MEMORYOS_MCP_ENDPOINT_URL` | `infrastructure/keycloak/configure-memoryos-realm.sh` | Scope, audience, trusted-app policy, narrowed default scopes, grant lifetime (see below) |
| `MEMORYOS_MCP_ADMIN_CLIENT_SECRET`, required with the endpoint URL | the same script | The `memoryos-mcp-admin` account through which the API keeps Keycloak's trusted hosts |
| Secret file `mcp_admin_client_secret` | `infrastructure/deployment/compose.base.yaml`, mounted into the API | The same value; without it *Quản trị › MemoryOS MCP* shows the trusted apps read-only |
| Exact `location` for the three paths | `web/nginx.conf` | `/mcp`, `/.well-known/oauth-protected-resource/mcp` and `/mcp/oauth/client-metadata.json` reach the API; anything else under `/mcp` stays with the web app |

What the realm reconciliation does with `MEMORYOS_MCP_ENDPOINT_URL`:

- **Scope.** Client scope `knowledge:read` with the endpoint audience.
- **Trusted apps.** A client policy admitting metadata documents from the hosts of the trusted apps, read by the
  image's `memoryos-client-id-metadata-document` executor, which ignores the property in ChatGPT's document that
  Keycloak 26.8's own executor rejects (keycloak/keycloak#51236). The first run seeds Claude's and ChatGPT's hosts
  (`claude.ai`, `claude.com`, `chatgpt.com`, and loopback for Claude Code's redirects); after that
  *Quản trị › MemoryOS MCP* owns the lists and a rerun keeps them
  ([ADR 0022](../decisions/0022-trusted-mcp-apps-kept-in-memoryos.md)). Resource indicators are limited to the
  endpoint, and a separate policy requires S256 PKCE from every public client of the realm, these included.
- **Admin account.** `memoryos-mcp-admin`, a confidential client with only a service account and the
  realm-management roles `manage-realm` and `manage-clients`; the script fails if it holds anything else.
- **The static ChatGPT client goes.** A client `memoryos-chatgpt` left by an earlier release is deleted, with its
  grants; ChatGPT connects through its metadata document.
- **Narrowed default scopes.** The realm's default client scopes lose `profile`, `email`, `roles` and `web-origins`.
- **Grant lifetime.** Offline sessions lapse after 30 days without use and end 180 days after the member connected,
  whatever the use (MEM-209).

## Keycloak 26.8 with `cimd`

`cimd` is a build option. An image built without it starts normally and advertises nothing, which is why
`smoke-test-image.sh` fails such an image in CI.

Every host gets Keycloak from the release ([CI and staging runbook](ci-cd.md#keycloak-runtime)).

- The first deployment after this change upgrades Keycloak from 26.7.0 to 26.8.0. The deployment dumps the `keycloak`
  database before the rollout, and Keycloak migrates its schema on start.
- That migration is one way. Rolling back to a 26.7 image needs the dump restored as well, not only the previous
  image.
- Sign-in is down for about a minute, as on every deployment.

**Staging** ran the Keycloak image it once shared with OrgMemory, pinned by `MEMORYOS_KEYCLOAK_IMAGE` in
`.env.staging`, until the release took it over. For that takeover:

1. **Proxy first.** `auth.kl3in.tech` must forward to `memoryos-keycloak`, an alias every deployment gives the
   container. The older `orgmemory-keycloak` alias no longer exists once this release is deployed.
2. **Deploy.** The release dumps the `keycloak` database into its transaction directory and recreates Keycloak from
   `memoryos-keycloak`. A leftover `MEMORYOS_KEYCLOAK_IMAGE` line in `.env.staging` has no effect and can be removed.
3. **Check it.**
   - `https://auth.kl3in.tech/realms/memoryos/.well-known/openid-configuration` contains
     `"client_id_metadata_document_supported":true`.
   - Sign in to MemoryOS.
4. **Keep the dump.** Copy `keycloak.dump` from the transaction directory to `/apps/memoryos-backups`; transaction
   directories are pruned.

**If the new Keycloak does not become healthy on that first deployment,** the deployment's rollback restores the api,
worker and web but not Keycloak, which it had not captured. Recover by hand:

1. Start the previous image again with the accepted configuration, naming it for this one command:
   `MEMORYOS_KEYCLOAK_IMAGE=<previous image> docker compose … up -d --no-deps --force-recreate --wait keycloak`.
   An image without the `memoryos` theme signs people in with Keycloak's own look until the release image returns.
2. If the 26.8 server had already migrated the schema, restore `keycloak.dump` into the `keycloak` database first.

## Realm reconciliation for the endpoint

Add these to the operator environment of [the realm reconciliation](development-runtime.md#reconcile-keycloak-owner-and-clients):

```text
MEMORYOS_MCP_ENDPOINT_URL        # exactly the browser origin followed by /mcp, e.g. https://app.vadan.app/mcp
MEMORYOS_MCP_ADMIN_CLIENT_SECRET # the memoryos-mcp-admin secret; required with the endpoint URL and only with it
```

**Create the admin account's secret** on the server, beside the other Keycloak secrets:

```sh
umask 077
openssl rand -hex 32 > /apps/memoryos/secrets/keycloak/mcp-admin-client-secret.txt
```

Every server needs this file, with or without an endpoint: Compose mounts it into the API as
`mcp_admin_client_secret`, and `deploy.sh` refuses a release whose secret files are missing. Create it before the
first release that mounts it. The file `mcp-chatgpt-client-secret.txt` of earlier releases is no longer read and can
be deleted once that release runs. Pass the same value to the script, so Keycloak and the API agree:

```sh
MEMORYOS_MCP_ADMIN_CLIENT_SECRET="$(cat /apps/memoryos/secrets/keycloak/mcp-admin-client-secret.txt)"
```

**Run the script.** The endpoint lines of its output are:

```text
client-scope=knowledge:read action=created|updated
client-scope=knowledge:read mapper=memoryos-mcp-endpoint-audience action=created|updated|unchanged
realm=memoryos default-client-scopes=[...] optional-client-scope=knowledge:read
realm=memoryos client-policy=memoryos-mcp-cimd resource=<endpoint>
client=memoryos-mcp-admin secret=updated roles=manage-realm,manage-clients
client=memoryos-chatgpt action=removed   # once, where an earlier release created it
```

**What the script refuses:**

- It stops when the endpoint is not the browser origin followed by `/mcp`, or when the endpoint and the admin
  account's secret do not come together.
- It stops when the client policy update fails because the running Keycloak lacks `cimd`. Check the image first.

**Other client policies.** Rerunning the script replaces only its own client profiles and policies (`memoryos-mcp-cimd` and `memoryos-mcp-cimd-pkce`)
and keeps any other in the realm.

**E-mail scope.** ChatGPT asks for `email`, so the script makes it an optional scope of the realm and of every client
whose client ID is a metadata document URL, those Keycloak already stores included. Without it ChatGPT's sign-in
stops with `invalid_scope`.

**Default scopes.** Narrowing the realm's default scopes changes only clients created afterwards. Clients that already
exist, `memoryos-web` among them, keep their scopes. The script gives every client it creates the classic set
explicitly.

## Trusted apps

*Quản trị › MemoryOS MCP › Cài đặt* is where an administrator with `MCP_MANAGE` switches Claude and ChatGPT on or off
and adds the organization's own apps by the domains of their client ID and of their document. Each change reaches
Keycloak at once; the API also reconciles the policy when it starts and every 10 minutes, so a host list edited in the
Keycloak console returns to MemoryOS's list. Switching an app off or removing it deletes the Keycloak clients built
from its documents, which revokes every member's connection through it.

When the page says the list cannot be changed, the API has no admin secret: check the secret file and that the realm
script ran with the same value. When a change fails with Keycloak unavailable, nothing was saved; retry once Keycloak
answers.

## Rate limits and activity

Each tool call counts against 300 a minute for one person in one app and 3,000 a minute for the endpoint
(`memoryos.mcp.endpoint.caller-calls-per-minute`, `global-calls-per-minute`); listing and discovery are free. A
refused call answers 429 with `Retry-After`, counts in `memoryos.mcp.endpoint.rate_limited` and shows under
*Hoạt động* as *Vượt giới hạn*. The activity log keeps 90 days of calls, without queries or documents; the worker
removes older rows every hour. The Chat & AI dashboard's *MemoryOS MCP endpoint* row draws the calls, their p95 and
the refusals.

## A release that changes the tools

Clients read the tool list when they connect. ChatGPT keeps the list it saw when the plugin was created or approved,
and a call to a tool that changed since can fail; Claude refreshes on reconnect. After a release that adds or changes
a tool, refresh the plugin in ChatGPT (a workspace administrator republishes a workspace app) and reconnect Claude's
connector.

The first release with `search_with_filters` also starts recording Drive and SharePoint dates. Until a Source runs
once after the deployment, its items have no date, so a recent period or one with an end leaves them out; a manual
sync of the Source fills them at once.

## Taking it back

The script has no removal path; it only reconciles what the environment asks for. To remove the endpoint's Keycloak
pieces, use the admin console of the `memoryos` realm:

1. Delete the client policies and profiles `memoryos-mcp-cimd` and `memoryos-mcp-cimd-pkce`.
2. Delete every client whose ID is a metadata document URL (`https://claude.ai/...`, `https://chatgpt.com/...` and
   those of the organization's own apps). Their grants go with them.
3. Delete client `memoryos-mcp-admin` and client scope `knowledge:read`.

The narrowed default scopes can stay; nothing but dynamically created clients reads them.
