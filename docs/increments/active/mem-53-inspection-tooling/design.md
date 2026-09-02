# MEM-53 design: local and staging inspection tooling

## Outcome

MemoryOS uses one explicit lifecycle for developer PostgreSQL and Redis services and one isolated staging path for database and stream inspection. Production contains no inspection tools.

```text
local development
  API -> owns PostgreSQL Dev Service on fixed host port 55432
  worker -> connects to that PostgreSQL and owns Redis Dev Service on fixed host port 56379
  optional local tools -> loopback-only pgweb and Redis Insight

staging
  base runtime -> PostgreSQL, Keycloak, API, worker, web
  staging overlay -> Mailpit plus private pgweb/Redis Insight
  loopback OAuth proxies -> the only host-exposed inspection endpoints

production
  base runtime + production overlay -> no inspection services
```

## Compose ownership

`compose.base.yaml` owns the environment-independent runtime graph: PostgreSQL, Keycloak, API, worker, web, durable volumes, and runtime networks. It contains no staging mail or inspection surface.

`compose.staging.yaml` adds:

- Mailpit and staging mail configuration;
- read-only PostgreSQL inspector bootstrap and ACL-provisioned TLS Redis;
- pgweb and Redis Insight on private inspection networks;
- one OAuth2 Proxy per tool;
- loopback bindings `127.0.0.1:18026` for pgweb and `127.0.0.1:18027` for Redis Insight.

`compose.production.yaml` supplies production-only runtime policy and deliberately adds no inspection services. `compose.local-tools.yaml` is standalone optional tooling for the fixed host Dev Service ports; its raw UIs bind only to loopback and do not require staging SSO.

Compose files are applied explicitly:

```text
staging:    compose.base.yaml + compose.staging.yaml
production: compose.base.yaml + compose.production.yaml
local tools: compose.local-tools.yaml
```

## Development service lifecycle

Arconia's automatic `dev` profile is renamed to `development` for both applications.

The API alone owns `arconia-dev-services-postgresql` in development. It publishes the PostgreSQL service on fixed port `55432`; Spring Boot's service connection supplies the API datasource. API tests disable the Dev Service because repository tests already own explicit isolated PostgreSQL containers where exact migration and concurrency control matters.

The worker development profile connects to the API-owned database at `jdbc:postgresql://localhost:55432/arconia` with Arconia's development credentials. It does not provision a second PostgreSQL container. The worker continues to own Redis Dev Services, now published on fixed port `56379`. Worker tests keep their existing isolated/test-managed service behavior and do not depend on a developer's long-lived ports.

Arconia 0.30 exposes fixed ports through Testcontainers and has no bind-address property; observed Docker publication is on the host interfaces, not loopback-only. The services remain development-only, and developers must keep the host firewall enabled on non-private networks. The optional browser tools themselves bind only to `127.0.0.1`.

This avoids Testcontainers cross-application reuse: no per-developer reuse flag, configuration-hash coupling, or manual reused-container cleanup becomes part of the repository contract.

## PostgreSQL inspection

Staging pgweb retains the existing `memoryos-pgweb` Keycloak client and `memoryos_pgweb` database role while moving the runtime into repository-owned Compose. The database role is created idempotently with CONNECT and schema/table/sequence read access plus default privileges for future tables. It has no write privilege.

The staging API waits for the bootstrap job before Flyway. Existing-table grants cover retained databases; default privileges are therefore installed before any new `memoryos_app` migration creates tables or sequences.

pgweb runs with:

- read-only mode;
- locked session connection;
- SSH disabled;
- bounded query timeout;
- credentials through a mounted passfile rather than command arguments;
- browser auto-open disabled.

The raw pgweb port is private. Its OAuth2 Proxy is the only service attached to the shared proxy network.

## Redis inspection

Staging Redis Insight uses the pinned Redis Insight image on port `5540`, persistent encrypted storage, explicit terms acceptance, disabled database-management actions, and one preconfigured MemoryOS connection. Its raw port is private.

A dedicated Redis ACL principal can inspect only the MemoryOS execution namespace and the reserved future cache namespace. It may run bounded read-only commands required by Redis Insight:

```text
PING HELLO INFO DBSIZE SCAN TYPE TTL GET MEMORY
COMMAND INFO; CLIENT SETNAME/SETINFO; XINFO XRANGE XLEN XPENDING
```

Write, keyspace-administration, ACL, CONFIG, MODULE, SCRIPT, FUNCTION, and connection-killing commands remain denied. The Redis entrypoint reconstructs the complete hashed ACL from file-backed credentials on every start, so restart or rotation cannot leave a divergent mutable user definition.

## Authentication and authorization

pgweb and Redis Insight use separate confidential Keycloak clients, callback URLs, client secrets, OAuth2 Proxy cookie secrets, and cookie names. Both use Authorization Code flow with PKCE S256 through OAuth2 Proxy's Keycloak provider.

A realm-local role `memoryos-inspector` is assigned only to the reconciled initial owner. Reusing the existing owner preserves its credential and remains compatible with the realm's email-as-username policy; no second privileged local account is created. Reconciliation revokes stale grants of this dedicated role from every other realm user before enforcing the single-owner invariant. The master realm and master bootstrap administrator are never exposed to inspection clients.

OAuth2 Proxy requires the `memoryos-inspector` role. A normal MemoryOS user can authenticate to the realm but cannot reach either tool.

## Network and secret boundaries

- only OAuth proxies join the shared proxy network in staging;
- pgweb joins only its proxy and PostgreSQL inspection networks;
- Redis Insight joins only its proxy and Redis inspection networks;
- raw tool ports are not published in staging;
- proxy ports bind to loopback for an operator-controlled upstream tunnel;
- staging tool credentials, cookie secrets, encryption keys, Redis ACL passwords, and TLS material are file-backed Compose secrets; Redis and the worker share one worker-password file, while OAuth client secret values enter only the controlled Keycloak reconciliation shell;
- no staging or production secret receives a checked-in default;
- production Compose and production application artifacts contain no Arconia Dev Services or inspection services.

The existing external `/apps/memoryos-pgweb` deployment remains stopped, not deleted, until the repository-owned staging path passes acceptance. It is rollback state, not a second active runtime.

## Pinned images

- `sosedoff/pgweb:0.17.0@sha256:a5256d416e2e8b92d69a4459058e3eca33a9f075d8325491644411d0bc3bd70b`
- `redis/redisinsight:3.8.0@sha256:b5e19ee240abef6edb435871b90ff8a210995422e8e018ab61c0339d318a1f84`
- `redis:8.2.1-alpine@sha256:987c376c727652f99625c7d205a1cba3cb2c53b92b0b62aade2bd48ee1593232` for staging Redis and ACL bootstrap
- existing repository-pinned OAuth2 Proxy `7.15.3`

## Verification

- render base+staging, base+production, and local-tool Compose combinations;
- inspect every changed Java, Kotlin DSL, YAML, properties, and XML file with JetBrains warnings enabled;
- compile affected modules;
- start the development API and worker and prove fixed shared PostgreSQL/Redis ports without duplicate service containers;
- start staging Compose and exercise tool health through each OAuth proxy;
- prove the realm-local initial owner is accepted and a user without `memoryos-inspector` is denied;
- prove pgweb cannot write to the MemoryOS database;
- prove the Redis inspector principal can inspect stream state but cannot write or administer Redis;
- prove production Compose contains no Mailpit, pgweb, Redis Insight, OAuth inspection proxy, or raw inspection port;
- run the repository gate after behavioral verification.

## Exclusions

- exposing raw database or Redis tool ports in staging/production;
- master-realm OAuth clients or broad Keycloak administrator authorization;
- committed bootstrap passwords, client secrets, cookie secrets, or encryption keys;
- database or Redis write access through inspection tools;
- Grafana/Prometheus/OTel observability work;
- deleting the stopped external pgweb deployment before staging acceptance;
- changing PostgreSQL or Redis business-authority semantics.
