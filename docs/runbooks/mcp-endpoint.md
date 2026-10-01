# MCP endpoint: Keycloak and routing

Operating procedure for the identity and routing side of the MemoryOS MCP endpoint
([MEM-114 design](../increments/active/mem-114-public-mcp-server/design.md)). Claude and ChatGPT reach `/mcp` on the
browser origin, sign the member in through Keycloak and receive a token whose audience is that URL.

## What has to be in place

| Piece | Where | Effect |
| --- | --- | --- |
| Keycloak 26.8 built with `cimd` | `infrastructure/keycloak/Dockerfile` | The realm advertises `client_id_metadata_document_supported`, so Claude identifies itself by its metadata document instead of registering a client per connection |
| Realm reconciliation with `MEMORYOS_MCP_ENDPOINT_URL` | `infrastructure/keycloak/configure-memoryos-realm.sh` | Scope, audience, Claude policy, narrowed default scopes, 30-day grants (see below) |
| Optional `MEMORYOS_MCP_CHATGPT_CLIENT_SECRET` | the same script | The confidential `memoryos-chatgpt` client |
| Exact `location` for the three paths | `web/nginx.conf` | `/mcp`, `/.well-known/oauth-protected-resource/mcp` and `/mcp/oauth/client-metadata.json` reach the API; anything else under `/mcp` stays with the web app |

What the realm reconciliation does with `MEMORYOS_MCP_ENDPOINT_URL`:

- **Scope.** Client scope `knowledge:read` with the endpoint audience.
- **Claude.** A client policy admitting metadata documents from `claude.ai` and `claude.com`. Redirects may also go to
  loopback for Claude Code. Resource indicators are limited to the endpoint, and a separate policy requires S256 PKCE from these public clients.
- **Narrowed default scopes.** The realm's default client scopes lose `profile`, `email`, `roles` and `web-origins`.
- **Grant lifetime.** Offline sessions lapse after 30 days without use.

## Keycloak 26.8 with `cimd`

`cimd` is a build option. An image built without it starts normally and advertises nothing, which is why
`smoke-test-image.sh` fails such an image in CI.

**Production** owns Keycloak through the release ([CI and staging runbook](ci-cd.md#keycloak-runtime)).

- The first promotion after this change upgrades Keycloak from 26.7.0 to 26.8.0. The deployment dumps the `keycloak`
  database before the rollout, and Keycloak migrates its schema on start.
- That migration is one way. Rolling back to the 26.7 image needs the dump restored as well, not only the previous
  image.
- Sign-in is down for about a minute, as on every deployment.

**Staging** runs the shared OrgMemory Keycloak image, named by `MEMORYOS_KEYCLOAK_IMAGE` in `.env.staging`, so a
release does not change it. Each step below needs the owner's approval when it is run.

1. **Rebuild the image.** Build the OrgMemory Keycloak image on `quay.io/keycloak/keycloak:26.8.0` with
   `KC_FEATURES=cimd` in its build stage, as this repository's Dockerfile does. Publish it and pin its digest in
   `.env.staging`.
2. **Back up.** Dump the shared `keycloak` database. Both the `memoryos` and `orgmemory` realms live in it.
3. **Recreate Keycloak.**
   ```sh
   docker compose -f infrastructure/deployment/compose.base.yaml -f infrastructure/deployment/compose.staging.yaml \
     up -d --no-deps --force-recreate --wait keycloak
   ```
   Sign-in to both products is down until it is ready.
4. **Check it.**
   - Confirm `https://auth.kl3in.tech/realms/memoryos/.well-known/openid-configuration` contains
     `"client_id_metadata_document_supported":true`.
   - Sign in to MemoryOS and to OrgMemory.

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

**Default scopes.** Narrowing the realm's default scopes changes only clients created afterwards. Clients that already
exist, `memoryos-web` among them, keep their scopes. The script gives every client it creates the classic set
explicitly.

## Taking it back

The script has no removal path; it only reconciles what the environment asks for. To remove the endpoint's Keycloak
pieces, use the admin console of the `memoryos` realm:

1. Delete the client policies and profiles `memoryos-mcp-cimd` and `memoryos-mcp-cimd-pkce`.
2. Delete client `memoryos-chatgpt` and every client whose ID is a `https://claude.ai/...` metadata URL. Their grants
   go with them.
3. Delete client scope `knowledge:read`.

The narrowed default scopes can stay; nothing but dynamically created clients reads them.
