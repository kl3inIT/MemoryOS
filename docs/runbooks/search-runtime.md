# Search runtime and recovery

Alias inspection uses `GET /<read-alias>/_alias/<read-alias>` so OpenSearch Security resolves only that alias's actual targets. The unscoped `GET /_alias/<alias>` can return 403 because it checks unrelated indexes; adding a cluster alias permission does not fix that target scope. The service role grants `indices:data/write/bulk` for bulk coordination while target-index permissions remain restricted to `memoryos-chunks*`. Human inspector permissions are unchanged. The local security-disabled integration fixture does not establish this production permission boundary.

## Development

Start the normal local OpenSearch service from the repository root:

```powershell
docker compose -f infrastructure/deployment/compose.search.local.yaml up -d
```

The pinned OpenSearch 3.8.0 service persists its data in a dedicated volume and publishes only `127.0.0.1:9200`. Authentication is disabled for this loopback development service. API and worker use their existing development launch paths, with Infisical `dev` supplying `SPRING_AI_OPENAI_API_KEY`. Set `MEMORYOS_SEARCH_REPLICAS=0` for this single-node environment; server deployments default to one replica and require enough data nodes to allocate it. Never use `down -v` as an ordinary restart.

Start API first for Flyway V17, then worker. Upload/reindex a FILE through the existing Source UI. Extraction produces canonical JSON; the SEARCH workload then produces chunks/vectors. Wait for the item's separate Search status to become Ready, then search from the application home and open its passages. No ChatModel or Chat configuration is needed.

## Server configuration

Both deployables read `classpath:memoryos-search.yaml`. Supply a managed OpenSearch cluster with HTTPS, its service username/password and a trusted CA (system trust or `MEMORYOS_OPENSEARCH_CA_CERTIFICATE`). The Java client rejects HTTP credentials and non-loopback HTTP endpoints. Credential values come from Infisical or deployment-mounted secrets, never this repository.

| Setting | Default / effect |
| --- | --- |
| `MEMORYOS_OPENSEARCH_ENDPOINT` | `http://127.0.0.1:9200` for development; set HTTPS on a server |
| `MEMORYOS_OPENSEARCH_USERNAME`, `MEMORYOS_OPENSEARCH_PASSWORD` | Service credentials; both or neither |
| `MEMORYOS_OPENSEARCH_CA_CERTIFICATE` | Optional mounted PEM CA bundle |
| `SPRING_AI_OPENAI_API_KEY` | Infisical secret shared by query and indexing embedding calls |
| `MEMORYOS_EMBEDDING_ENDPOINT` | `https://api.openai.com/v1` |
| `MEMORYOS_EMBEDDING_MODEL`, `MEMORYOS_EMBEDDING_DIMENSIONS` | `text-embedding-3-large`, `3072`; changes select a different index |
| `MEMORYOS_EMBEDDING_BATCH_SIZE`, `MEMORYOS_EMBEDDING_CONCURRENCY` | `32`, `2` per deployable |
| `MEMORYOS_SEARCH_CANDIDATE_LIMIT`, `MEMORYOS_SEARCH_KEYWORD_WEIGHT` | `500`, `0.5`; exploration/response budget and native min-max/arithmetic-mean fusion weight |
| `MEMORYOS_SEARCH_MINIMUM_SEMANTIC_SCORE` | `0.70`; raw Faiss cosine-space score floor before fusion (`0.40` cosine similarity). Re-evaluate with representative judgments when changing model/corpus |
| `MEMORYOS_SEARCH_TIMEOUT` | `30s` provider/response timeout; OpenSearch connect timeout is 3 seconds |
| `MEMORYOS_SEARCH_INDEX_PREFIX` | `memoryos-chunks`; changing it forces a distinct projection |
| `MEMORYOS_SEARCH_REPLICAS` | `1`; use `0` explicitly for single-node development |
| `MEMORYOS_WORKER_SEARCH_BATCH_SIZE` | `2` SEARCH deliveries in a worker batch |

The worker needs index/mapping/alias creation, native bulk/search/count/delete and search-pipeline administration scoped to MemoryOS resources. The API needs read/metadata access; use separate cluster roles where available. No cluster-admin credential belongs in the browser. Snapshot administration is an operator responsibility.

Readiness probes retain the existing database/Redis/object-store contracts. Search availability is established by a query and worker indexing outcomes; an API liveness/readiness response alone does not prove the embedding provider or search backend works.

## Staging OpenSearch and Dashboards

Use `compose.base.yaml`, `compose.staging.yaml` and `compose.search.staging.yaml` together. The Search overlay provides pinned OpenSearch/Dashboards 3.8.0, a persistent index volume, native Security TLS and a separate internal Dashboards server identity. Neither service publishes a host port; Nginx Proxy Manager forwards the exact HTTPS Dashboards origin to `memoryos-opensearch-dashboards:5601` on `proxy-network`.

1. As deployment UID 1000, set the Search values from `staging.env.example`, then run `python3 infrastructure/opensearch/provision-staging.py`. This atomically creates an outside-Git secret directory on first use; later runs preserve passwords/certificates and reconcile managed Security YAML. It refuses a partial secret set. Back up this directory securely; node/admin certificates expire after one year and require a coordinated certificate replacement before expiry.
2. Store `MEMORYOS_OPENSEARCH_ENDPOINT=https://opensearch:9200`, `MEMORYOS_OPENSEARCH_USERNAME=memoryos-service`, the generated service password, and `MEMORYOS_SEARCH_REPLICAS=0` in Infisical staging. The overlay mounts the public CA at the plain filesystem path consumed by the Java client. Keep `SPRING_AI_OPENAI_API_KEY` in Infisical and verify the actual embedding endpoint accepts it.
3. Start only `opensearch` with `up -d --no-deps opensearch`, then explicitly run `--profile ops run --rm search-security-bootstrap`. This uploads the managed configuration using the admin client certificate; that certificate/key is mounted only in the short-lived bootstrap container. Repeating bootstrap reconciles those managed files, so preserve changes there rather than making unrecorded Security API edits.
4. Run `configure-keycloak-client.py` with `KEYCLOAK_URL`, `KEYCLOAK_ADMIN_USERNAME`, `KC_CLI_PASSWORD` and the Search environment supplied by the controlled operator shell. It reconciles only client `memoryos-opensearch-dashboards`, exact callback `<origin>/auth/openid/login`, audience and role claim, and its `memoryos-inspector` role scope. It does not grant or remove roles from users.
5. Start `opensearch-dashboards`. With `MEMORYOS_OPENSEARCH_DASHBOARDS_PUBLIC_URL` set to the exact HTTPS origin, run `python3 infrastructure/opensearch/install-dashboards-proxy.py` as the existing server Docker operator. It uses NPM's supported `/data/nginx/custom/http.conf` include, validates Nginx before reload, and obtains the exact-domain certificate through the existing Certbot account. The managed host is a custom Nginx configuration, so it is maintained through this script rather than the NPM Proxy Hosts UI. An operator crontab entry runs `/apps/memoryos/renew-search-certificate.sh` twice daily; Certbot only renews when due and validates/reloads Nginx after renewal. The installer preserves unrelated custom includes and cron entries and rolls back the custom proxy configuration if installation fails. Native OpenID Connect validates the existing MemoryOS realm issuer and Dashboards audience. Only the existing `memoryos-inspector` role maps to human Dashboard access plus read-only `memoryos-chunks*` data permissions. The internal Dashboards identity manages its saved-object indices; application credentials cannot administer Security or access unrelated indices. Dashboards passwords/OIDC/cookie keys are loaded from mounted files, with full CA/hostname verification and secure browser cookies.
6. As the existing root Docker operator, run `python3 infrastructure/opensearch/provision-dashboards.py`. It calls the supported Dashboards Saved Objects API over verified container-local HTTPS, selects `global_tenant`, and reconciles stable `memoryos-chunks` index-pattern and `memoryos-chunks-inspection` saved-search IDs. Replays make no write when the controlled fields already match. The curl credential file is generated from the existing server password by `provision-staging.py`, mounted read-only and never printed or passed as a process argument. Basic authentication exists only for this operator API path; unauthenticated browser pages still redirect to OpenID Connect by default. Do not write `.kibana*` directly.
7. Verify owner SSO opens `/app/discover#/view/memoryos-chunks-inspection`, the four sample chunks are visible, ordinary users are denied, the application can access only its Search indexes, and the inspector cannot create/update saved objects or write/delete documents. Record the actual checks in Linear. The single-node/zero-replica setup is staging availability only; it does not establish HA or snapshot acceptance.
8. Back up PostgreSQL before applying V17, retain prior image IDs and environment configuration, then deploy API/worker/web images built from the same PR SHA. Verify image revision labels, API/worker health, the public browser build, and an authenticated Search query. Health alone does not prove Search; record embedding 401/outage separately if a valid key is still unavailable.

Rollback the application with the recorded prior image versions and keep the additive V17 tables unless database recovery is explicitly required. Stop Dashboards/remove its proxy to withdraw the inspection UI. Preserve the Search volume and secret directory; normal rollback never runs `down -v`.

### Credential, role and certificate boundaries

Embedding credentials require an HTTPS base URL. The Keycloak administration script accepts only the shared trusted origin `https://auth.kl3in.tech` (optional explicit port 443), rejects userinfo/paths/query/fragment and refuses redirects before forwarding credentials. Search API response DTOs declare every guaranteed response field required; regenerate the OpenAPI snapshot and Hey API client from the live Spring schema after contract changes.

NPM connects to Dashboards with HTTPS, verifying the certificate against the public Search CA and the `memoryos-opensearch-dashboards` hostname. Dashboards has a separate server certificate/key; it does not receive the OpenSearch node/admin keys. `memoryos_search_inspector` grants read-only saved-object index access and `kibana_all_read` for the global tenant; the human inspector mapping never includes `kibana_user`. The `kibana_read_only` marker additionally limits the UI, but is not the backend permission boundary. OIDC remains the default browser authentication route. The colocated Basic route is required only for the controlled Saved Objects call using the existing internal Dashboards server identity; its credential stays inside the protected secret mount.

The proxy installer installs a daily 03:41 operator cron for `/apps/memoryos/renew-internal-search-certificates.py --renew-certificates`. This is the checked-in `provision-staging.py` copied to a stable path. Renewal checks node/admin/Dashboards leaf certificates, replaces those expiring within 30 days, retains the CA/DNs and service credentials, and backs up the old leaf pairs under the protected secret directory. It restarts the affected OpenSearch/Dashboards containers, waits for health and restores the old pairs on reload failure. A renewed admin certificate uses the unchanged trusted CA and admin DN; `search-security-bootstrap` reads the current leaf on its next invocation, so renewal does not rewrite Security configuration. Run the same command manually for overdue renewal; `--within-days 366` forces a controlled rotation drill. The CA has a separate 10-year validity and must be replaced through a coordinated trust-distribution change. Keep renewal logs under `/apps/memoryos` and verify cron health during operational checks.

### V17 deployment window

PostgreSQL rewrites `documents` for the volatile `gen_random_uuid()` default and takes an exclusive table lock. V17 is a coordinated maintenance migration, not an online large-table backfill. Before first application, take a verified backup, inspect table/index size, stop writers and measure the migration on a representative copy within the maintenance window; an unvalidated large-corpus production rollout is outside this staging acceptance. Splitting statements or looping batches inside the same Flyway transaction does not release its DDL lock. V17 has already been applied on the two-document staging database; preserve its checksum. A future large installation needs an accepted expand/backfill/contract release sequence before rollout, rather than editing an applied migration. See [PostgreSQL 18 ALTER TABLE](https://www.postgresql.org/docs/18/sql-altertable.html).

## Recovering the projection

1. Preserve PostgreSQL and canonical artifacts. Determine the configured physical index identity from the index `_meta` and current `documents.search_index_identity`.
2. If the index remains readable, restart normal workers. Matching input hashes reuse surviving vectors; only missing inputs are embedded. The reconciliation cursor revisits current Documents and verifies indexed chunk counts before readiness.
3. If a compatible OpenSearch repository snapshot exists, restore it using the cluster's native snapshot API, including the intended mapping/alias. Keep the model/input space unchanged. Let the normal worker reconcile current PostgreSQL generations, replace missing data and sweep deleted/obsolete records. A successful snapshot restore is not current-state completeness.
4. If both index and snapshot are lost, the normal worker recreates the physical index, reads current PostgreSQL chunks (or checksum-verified canonical JSON), and regenerates embeddings. This requires a functioning provider and incurs embedding cost. Missing canonical artifacts require the existing Source reindex flow.
5. Verify a representative exact-code query, Vietnamese paraphrase, table value and recently deleted document. Record index bytes/replica allocation, corpus token count, embedding cost, elapsed rebuild time and missing/failed document counts in Linear before claiming deployment recovery.

For a MinIO-backed snapshot repository, install/configure the OpenSearch `repository-s3` plugin and its server-side keystore credentials, give it a dedicated snapshot bucket/prefix, configure the S3-compatible endpoint and region, then register and verify the native repository. These credentials are separate from MemoryOS's ordinary file access identity. Run a create/restore/catch-up drill in an isolated target before setting an RPO/RTO. This change does not provision or claim a tested server snapshot repository. Follow the [native OpenSearch snapshot documentation](https://docs.opensearch.org/latest/tuning-your-cluster/availability-and-recovery/snapshots/snapshot-restore/).

Old physical indices are not automatically deleted when the model/input space changes. Retain them until the new projection and recovery path are verified; then remove only the explicitly retired identity. The current implementation gradually rebuilds under the configured identity and does not promise uninterrupted search during model migration.
