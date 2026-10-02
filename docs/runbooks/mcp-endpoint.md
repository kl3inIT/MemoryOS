# MCP endpoint: Keycloak and routing

Operating procedure for the identity and routing side of the MemoryOS MCP endpoint
([MEM-114 design](../increments/active/mem-114-public-mcp-server/design.md)). Claude and ChatGPT reach `/mcp` on the
browser origin, sign the member in through Keycloak and receive a token whose audience is that URL.

## What has to be in place

| Piece | Where | Effect |
| --- | --- | --- |
| Keycloak 26.8 built with `cimd` and the `memoryos-client-id-metadata-document` executor | `infrastructure/keycloak/Dockerfile` | The realm advertises `client_id_metadata_document_supported`, so Claude and ChatGPT each identify themselves by a metadata document instead of registering a client per connection |
| Realm reconciliation with `MEMORYOS_MCP_ENDPOINT_URL` | `infrastructure/keycloak/configure-memoryos-realm.sh` | Scope, audience, Claude policy, narrowed default scopes, 30-day grants (see below) |
| Optional `MEMORYOS_MCP_CHATGPT_CLIENT_SECRET` | the same script | The confidential `memoryos-chatgpt` client |
| Secret file `mcp_chatgpt_client_secret` | `infrastructure/deployment/compose.base.yaml`, mounted into the API | The same value, which *Quản trị › MemoryOS MCP* shows a ChatGPT workspace administrator |
| Exact `location` for the three paths | `web/nginx.conf` | `/mcp`, `/.well-known/oauth-protected-resource/mcp` and `/mcp/oauth/client-metadata.json` reach the API; anything else under `/mcp` stays with the web app |

What the realm reconciliation does with `MEMORYOS_MCP_ENDPOINT_URL`:

- **Scope.** Client scope `knowledge:read` with the endpoint audience.
- **Claude and ChatGPT.** A client policy admitting metadata documents from `claude.ai`, `claude.com` and
  `chatgpt.com`, read by the image's `memoryos-client-id-metadata-document` executor, which ignores the property in
  ChatGPT's document that Keycloak 26.8's own executor rejects (keycloak/keycloak#51236). Redirects may also go to
  loopback for Claude Code. Resource indicators are limited to the endpoint, and a separate policy requires S256 PKCE
  from every public client of the realm, these included.
- **Narrowed default scopes.** The realm's default client scopes lose `profile`, `email`, `roles` and `web-origins`.
- **Grant lifetime.** Offline sessions lapse after 30 days without use.

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
MEMORYOS_MCP_ENDPOINT_URL          # exactly the browser origin followed by /mcp, e.g. https://app.vadan.app/mcp
MEMORYOS_MCP_CHATGPT_CLIENT_SECRET # optional; creates memoryos-chatgpt. Needs MEMORYOS_MCP_ENDPOINT_URL
```

**Create the ChatGPT secret** on the server, beside the other Keycloak secrets:

```sh
umask 077
openssl rand -hex 32 > /apps/memoryos/secrets/keycloak/mcp-chatgpt-client-secret.txt
```

Every server needs this file, with or without an endpoint: Compose mounts it into the API as
`mcp_chatgpt_client_secret`, and `deploy.sh` refuses a release whose secret files are missing. The API shows it only
beside an endpoint URL. Pass the same value to the script, so Keycloak and the administration page agree:

```sh
MEMORYOS_MCP_CHATGPT_CLIENT_SECRET="$(cat /apps/memoryos/secrets/keycloak/mcp-chatgpt-client-secret.txt)"
```

**Run the script.** The endpoint lines of its output are:

```text
client-scope=knowledge:read action=created|updated
client-scope=knowledge:read mapper=memoryos-mcp-endpoint-audience action=created|updated|unchanged
realm=memoryos default-client-scopes=[...] optional-client-scope=knowledge:read
realm=memoryos client-policy=memoryos-mcp-cimd resource=<endpoint>
client=memoryos-chatgpt secret=updated scopes=knowledge:read,offline_access
```

**What the script refuses:**

- It stops when the endpoint is not the browser origin followed by `/mcp`, or when the ChatGPT secret comes without
  the endpoint.
- It stops when the client policy update fails because the running Keycloak lacks `cimd`. Check the image first.

**Other client policies.** Rerunning the script replaces only its own client profiles and policies (`memoryos-mcp-cimd` and `memoryos-mcp-cimd-pkce`)
and keeps any other in the realm.

**E-mail scope.** ChatGPT asks for `email`, so the script makes it an optional scope of the realm and of every client
whose client ID is a metadata document URL, those Keycloak already stores included. Without it ChatGPT's sign-in
stops with `invalid_scope`.

**Default scopes.** Narrowing the realm's default scopes changes only clients created afterwards. Clients that already
exist, `memoryos-web` among them, keep their scopes. The script gives every client it creates the classic set
explicitly.

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
2. Delete client `memoryos-chatgpt` and every client whose ID is a `https://claude.ai/...` metadata URL. Their grants
   go with them.
3. Delete client scope `knowledge:read`.

The narrowed default scopes can stay; nothing but dynamically created clients reads them.
