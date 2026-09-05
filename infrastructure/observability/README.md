# Staging observability

Run this Compose project independently of the MemoryOS application. It owns Grafana,
Loki, Tempo, Prometheus and the OpenTelemetry Collector. The application sends OTLP
HTTP to `memoryos-otel-collector:4318` on the private `memoryos-telemetry` network.
No backend or ingest port is published. Grafana alone joins the existing proxy network.

## First deployment

1. Use the reviewed commit and published API/worker image digests. Ensure the existing
   MemoryOS Keycloak realm and non-composite `memoryos-inspector` role exist. The
   existing realm provisioner owns assigning that role to the initial owner.
2. Reserve at least 4 GiB RAM for this stack, in addition to application services,
   and 20 GiB free disk to begin. These are initial deployment budgets, not capacity
   guarantees: check actual disk growth and memory after the first day's traffic.
   Prometheus has a 4 GiB retention cap; logs/traces have time retention and need
   filesystem monitoring. Do not deploy on a host without this headroom.
3. As the Linux deployment administrator, run
   `sh infrastructure/observability/provision-secrets.sh`. It preserves existing
   values or generates two distinct random secrets outside Git, with directory
   mode 0700 and files owned by Grafana UID/GID 472:472 with mode 0400. Compose
   bind-secret mounts preserve host ownership; root-owned 0600 files cannot be
   read by Grafana. Never print secret values in command logs.
4. Copy `observability.env.example` to a deployment-owned env file and set exact
   public URL, realm issuer, secret paths, and existing proxy network. URLs must
   have no trailing slash. Keep the client secret file when redeploying.
5. Provision SSO using Python 3 (standard library only), with `KEYCLOAK_URL` set
   to the Keycloak server origin, `KEYCLOAK_ADMIN_USERNAME`, `KC_CLI_PASSWORD`,
   `MEMORYOS_GRAFANA_PUBLIC_URL`, and `MEMORYOS_GRAFANA_OIDC_SECRET_FILE` supplied
   through the existing secret-management boundary, as a deployment administrator
   who can read the client secret file:

   ```sh
   python3 infrastructure/observability/configure-grafana-sso.py
   ```

   This reconciles only `memoryos-grafana`, enforces Authorization Code + S256 PKCE,
   a single `/login/generic_oauth` callback, and a scoped role claim in ID/access
   tokens and userinfo. It does not grant user roles. Run it before Grafana starts
   and whenever its URL/client secret changes. A missing inspector role fails
   provisioning rather than creating new user authority.
   Token and admin API requests reject redirects, including redirects to the
   same origin. Configure the final Keycloak origin directly.
6. Start the independent stack **before** deploying the staging application:

   ```sh
   docker compose --env-file /apps/memoryos/observability.env \
     -f infrastructure/observability/compose.observability.yaml config --quiet
   docker compose --env-file /apps/memoryos/observability.env \
     -f infrastructure/observability/compose.observability.yaml up -d
   ```

7. Configure the existing HTTPS reverse proxy for the exact Grafana public host
   with upstream `http://memoryos-grafana:3000`. Forward the original Host,
   `X-Forwarded-Proto: https`, client address, and WebSocket Upgrade headers for
   `/api/live/`. Do not expose Loki, Tempo, Prometheus or Collector publicly.
8. Check each service from Grafana's backend network:

   ```sh
   docker compose --env-file /apps/memoryos/observability.env \
     -f infrastructure/observability/compose.observability.yaml exec -T grafana sh -ec '
       for url in http://localhost:3000/api/health http://collector:13133/ \
         http://loki:3100/ready http://tempo:3200/ready http://prometheus:9090/-/ready; do
         wget -q -O /dev/null "$url"
       done'
   ```

   Readiness may need a short startup interval. Run this check after each restart
   and during incident diagnosis. The application must remain independent of it.
9. Set `MEMORYOS_RELEASE` to the application Git SHA in the application staging
   environment; keep `MEMORYOS_TELEMETRY_NETWORK=memoryos-telemetry`. Validate the
   normal base + staging Compose configuration, run the existing migration/API
   rollout, then worker rollout. Migration V10 only adds nullable telemetry
   columns; API remains the Flyway owner. Existing rows and old Redis messages
   remain valid, including during a rolling update.

## Acceptance before declaring staging operational

- In a fresh browser session, initial owner reaches Grafana as organization Admin
  and an ordinary Keycloak user is rejected. Native SSO may create authorized
  Grafana accounts; local public signup and anonymous access remain disabled.
  Inspector does not grant Grafana Server Admin. Revocation is enforced on next
  OAuth login; revoke existing Grafana sessions explicitly for immediate removal.
- Keep the separately protected local administrator as break-glass access when
  Keycloak/PostgreSQL is unavailable. Test it before disabling or rotating anything.
- Query API and worker JVM metrics, generate a real request and FILE operation,
  and follow the trace/log links. Worker retries have independent traces linked
  to the request origin. Search by `operation_id` across retries.
- Stop Collector briefly: requests and durable processing must still complete;
  application health must remain independent. Restart it and verify new telemetry.
  Queues are bounded and memory-backed. Telemetry can be dropped during prolonged
  outages or abrupt process death; rotated stdout is a fallback, not duplicate
  Loki ingestion. No lossless delivery guarantee is made.
- Restart backend containers without deleting volumes and verify stored data and
  provisioned dashboards. Check disk growth, memory pressure and seven-day
  log/trace versus fifteen-day metric retention on the staging host.
- Prometheus evaluates repository alert rules; view firing alerts in Grafana's
  Prometheus datasource/Prometheus API. No external notification destination has
  been configured; do not treat these rules as on-call paging.

## Upgrade and rollback

Record image digests, backing up Grafana's SQLite database and backend volumes
with the services stopped for a consistent snapshot. Validate new image configs,
start the stack, run readiness, OAuth and three-signal acceptance. Retain previous
  configuration and snapshots until acceptance passes. Backend storage formats can
change: restore the matching snapshot when reverting a major backend version;
do not mount upgraded data blindly into an older image. Application rollback may
leave V10's nullable columns in place. Never use `down --volumes` on staging.

Versions and local verification are recorded in the MEM-57 increment. Frontend
SDK work is MEM-58; no Spring AI runtime or external SaaS is required here.
