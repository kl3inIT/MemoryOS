# Implementation plan

One pull request.

- [x] **Realm and deployment.** The script creates `memoryos-mcp-admin` with exactly `manage-realm` and
  `manage-clients`, seeds the trusted hosts once and keeps the live lists on a rerun, sets the 180-day offline
  maximum and deletes `memoryos-chatgpt`; Compose mounts `mcp_admin_client_secret` instead of the ChatGPT secret.
  Tests: `test_realm_mcp_endpoint.py`, `test_server_secret_sources.py`.
- [x] **Trusted apps.** V140; `McpTrustedAppService` with validation, revisions, audit and the Keycloak projection;
  `KeycloakMcpClientPolicy` behind the `McpClientPolicy` port; reconciliation at start and every 10 minutes; API
  routes under `/api/mcp/endpoint/trusted-apps`; the member connection lists the apps that are on. Tests:
  `KeycloakMcpClientPolicyTest`, `McpEndpointIntegrationTest`.
- [x] **Activity and insights.** `mcp_endpoint_calls`, `McpEndpointActivity` written by every tool call and every
  refusal for rate, keyset pages, insights by day, app and tool, the worker's 90-day retention task. Tests:
  `McpEndpointIntegrationTest`, `ControlPlaneIntegrationTest`.
- [x] **Rate limits** 300 a minute per person and app, 3,000 for the endpoint; the refusal counter.
- [x] **Web.** *Cài đặt*, *Hoạt động* and *Thống kê* tabs with their filters in the address; the member guide follows
  the trusted apps. Tests: `mcp-endpoint-admin-page.test.tsx`, `mcp-endpoint-settings-page.test.tsx`,
  `mcp-endpoint.spec.ts` with captures in both themes at 1280 and 390 px, reviewed.
- [x] **Dashboard.** The *MemoryOS MCP endpoint* row of Chat & AI.
- [x] **Browser session.** 8 hours unused, 7 days from the password (`auth_time`) with Keycloak's SSO session at
  the same 7 days. Tests: `SessionSecurityIntegrationTest`, `RealmSessionLifetimeTest`.
- [x] **Documents.** [ADR 0020](../../../decisions/0020-trusted-mcp-apps-kept-in-memoryos.md), the audit spec and
  matrix, the runbooks, the MEM-114 increment, the roadmap.
- [ ] CI green.
- [ ] **Staging, before the merge**, each step with the owner's approval, so the API never runs without its account:
  1. Create `/apps/memoryos/secrets/keycloak/mcp-admin-client-secret.txt` (`deploy.sh` refuses a release whose secret
     files are missing).
  2. Run this branch's realm script with `MEMORYOS_MCP_ADMIN_CLIENT_SECRET`. It talks only to Keycloak, so the running
     release is untouched: it creates the account, keeps the live trusted hosts, sets the 180-day maximum and the
     7-day SSO session, and deletes `memoryos-chatgpt`.
- [ ] Merge after the owner's approval; the deployment follows.
- [ ] **Staging, after the deployment:** the page lists Claude and ChatGPT as manageable; switching ChatGPT off and on
  revokes and restores its connection; a call appears under *Hoạt động*; the dashboard row draws; after more than
  8 hours away MemoryOS opens again without the password.
- [ ] **Production:** the same secret file and realm run before the next promotion.
