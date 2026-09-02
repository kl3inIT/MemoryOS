# MEM-53 verification: local and staging inspection tooling

Verified locally on 2026-09-02. Public staging SSO acceptance remains a deployment gate because this workstation has no configured SSH access to the staging host.

## Development service lifecycle

| Contract | Evidence |
| --- | --- |
| Arconia activates the repository profile rather than its default | Real `:api:bootRun` and `:worker:bootRun` processes each logged exactly one active profile: `development`. `META-INF/arconia-bootstrap.properties` owns the early bootstrap setting. |
| API alone owns development PostgreSQL | The real API started an Arconia PostgreSQL 18.4 Dev Service on fixed host port `55432`, applied Flyway, completed Tenant bootstrap, and returned `{"status":"UP"}` from `/actuator/health`. |
| Worker reuses the API database and owns development Redis | With the API still running, the real worker started without another PostgreSQL container, opened its datasource, started db-scheduler, started one Redis 8.8 Dev Service on fixed host port `56379`, and returned `{"status":"UP"}` from `/actuator/health/readiness`. |
| Tests do not inherit the fixed PostgreSQL lifecycle | The API Gradle test task explicitly disables PostgreSQL Dev Services. Existing isolated PostgreSQL and H2 test contracts remained non-skipped and green. |

Arconia 0.30 has no fixed-port bind-address property. Docker inspection showed Testcontainers publishes the two development ports on host interfaces, not loopback-only. The runbook and architecture record the developer-firewall requirement; the optional browser tools remain loopback-only.

## Compose and inspection boundaries

- Base+staging, base+production, and local-tool Compose combinations each completed `config --quiet` with validation-only values.
- Base+production rendered exactly `postgres`, `shared-keycloak`, `api`, `worker`, and `web`; it contained no Mailpit, Redis inspector, pgweb, Redis Insight, or inspection OAuth proxy.
- Base+staging rendered Mailpit, TLS Redis, both idempotent inspector bootstrap jobs, pgweb, Redis Insight, and separate OAuth2 Proxies in addition to the base runtime.
- Rendered staging configuration reported no published ports for pgweb or Redis Insight. Only their proxies published `127.0.0.1:18026` and `127.0.0.1:18027`; raw tools joined only their private backend networks, while proxies alone also joined `proxy`.
- Rendered worker configuration retained its API-health dependency, added Redis-health dependency, selected `production,staging`, mounted only the Redis CA, and configured the `memoryos-worker` TLS connection.
- Pinned image digests are recorded for pgweb 0.17.0, Redis Insight 3.8.0, Redis 8.2.1 Alpine, and OAuth2 Proxy 7.15.3.

## Behavioral inspection evidence

| Contract | Evidence |
| --- | --- |
| Secret provisioning is bounded and idempotent | The Linux provisioner ran twice over the same directories, preserved the complete set, verified the generated Redis certificate against its CA, and printed no secret values. |
| PostgreSQL inspection is read-only | A disposable base+staging runtime created `memoryos_pgweb`, reran the bootstrap over the existing role, and started pgweb under the Compose security constraints. pgweb returned HTTP `200`. The role reported `current_user=memoryos_pgweb` and `transaction_read_only=on`; `CREATE TABLE` failed with `cannot execute CREATE TABLE in a read-only transaction`. |
| Redis transport is TLS and inspector ACL is persistent | A disposable base+staging runtime started Redis with plaintext port disabled, TLS on `6379`, default user disabled, hashed ACL credentials, and successful inspector reconciliation. Restart reconstruction is sourced from the mounted secret files rather than mutable container state. |
| Redis inspection can read but cannot mutate/administer | `memoryos-inspector` read a seeded `memoryos:execution:*` stream through `XRANGE`. `XADD` returned `NOPERM`; `CONFIG GET` returned `NOPERM`. The ACL also denies unrestricted key patterns and all commands not explicitly listed. |
| Redis Insight starts with the checked-in restrictions | Redis Insight returned `200 {"status":"up"}` from `/api/health/` under the Compose read-only filesystem/capability constraints. Its API reported the preconfigured `memoryos-inspector` connection with TLS enabled and password material present; Compose supplies the documented persistent encryption key and disables database management. |

## Keycloak reconciliation evidence

`sh -n` passed for the Keycloak, PostgreSQL, Redis, and secret-provisioning scripts. Both inspection client JSON templates parsed successfully.

A disposable Linux `kcadm` double exercised the complete existing-user reconciliation path. It observed:

- one `memoryos-inspector` user-role grant, targeted only to realm-local user UUID `uuid-admin`;
- client scope grants only to `memoryos-pgweb` and `memoryos-redisinsight`;
- separate confidential clients with `fullScopeAllowed=false`;
- exact callbacks `https://pgweb.example.test/oauth2/callback` and `https://redis.example.test/oauth2/callback`;
- mandatory S256 PKCE on both clients;
- no requirement for admin email/password when the realm-local `admin` already exists; and
- no secret value in reconciliation stdout.

OAuth2 Proxy configuration requires `memoryos-inspector` independently for both tools and uses separate client secrets, cookie secrets, and cookie names. Actual positive `admin` login and negative ordinary-user denial cannot be claimed until this reviewed change is deployed and Keycloak/Nginx Proxy Manager are reconciled on staging. The external `/apps/memoryos-pgweb` stack must remain stopped but intact until that gate passes.

## Static, test, and artifact gates

- JetBrains inspections with warnings enabled reported no findings in changed Kotlin DSL, TOML, YAML, JSON, or ordinary application configuration files.
- IntelliJ reported `Unused property` for each `META-INF/arconia-bootstrap.properties` entry. This is a tooling false positive: Arconia's `BootstrapConfigurationFile` loads that exact resource reflectively before normal configuration import, and the real API/worker launches proved the resulting single `development` profile. No suppression was added.
- `./gradlew.bat :api:compileJava :worker:compileJava --no-daemon` completed successfully.
- `./gradlew.bat clean check --no-daemon` completed successfully: 23 actionable tasks, 13 executed, and 10 from cache.
- The 38 generated test-suite reports contain 141 tests with `skipped="0"`, `failures="0"`, and `errors="0"`.
- `./gradlew.bat :api:bootJar :worker:bootJar --no-daemon` completed successfully. Archive inspection found no Arconia Dev Services or Testcontainers artifacts in either production JAR; API retained only its intended Arconia multitenancy libraries.
