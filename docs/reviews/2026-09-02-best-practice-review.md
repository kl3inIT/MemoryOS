# Best-practice review — 2026-09-02

Snapshot of a full-codebase best-practice review run after the 2026-09-02 simplification pass (reuse, simplification, efficiency, altitude, naming). Three passes ran independently: JVM backend, web frontend, and infrastructure/delivery. Every finding below was checked against the working tree; none has been applied. Each item carries a risk label:

- `safe`: idiom or configuration change with no observable behavior change.
- `behavior`: changes runtime behavior, a contract, a schema, or requires a coordinated rollout.

Items are candidates, not decisions. Promote an item into an increment before implementing it; record anything that changes a contract in the owning spec.

## A. High priority: latent runtime failures

| # | Finding | Location | Fix | Risk |
| --- | --- | --- | --- | --- |
| A1 | nginx never sets `client_max_body_size`; the default 1 MB means any FILE upload above 1 MB gets an nginx HTML 413 instead of the API's typed 413, although the API accepts 10 MiB. | `web/nginx.conf` API location; `api/src/main/resources/application.yaml` multipart limits | `client_max_body_size 11m;` (match `max-request-size`) | behavior |
| A2 | HikariCP defaults (10 per runtime) sum to exactly the `memoryos_app` role's `CONNECTION LIMIT 20`. A rolling restart or one extra session exhausts the role and Flyway fails at startup. | `infrastructure/postgres/bootstrap-shared-databases.sh`; api/worker `application.yaml` (no `spring.datasource.hikari.*`) | Explicit `maximum-pool-size` (api 8, worker 4) or raise the limit to 30 | behavior |
| A3 | Worker scheduling has no `spring.task.scheduling.shutdown.await-termination`; a deploy mid-extraction closes the extractor, kills the child JVM, and the coordinator records a permanent `FAILED` instead of letting the 120 s lease expire and be reclaimed. Compose already grants `stop_grace_period: 120s`. | `worker/src/main/resources/application.yaml`; `IngestionWorker`; `TikaSourceContentExtractor.close`; `DefaultIngestionCoordinator` | `await-termination: true`, `await-termination-period: 100s`; do not map the post-close failure to `fail(...)` | behavior |
| A4 | Lease timestamps mix worker JVM time (`Instant.now()` for `:now` and `lease_expires_at`) with database time (`CURRENT_TIMESTAMP` for `started_at`, `completed_at`). Two workers with skewed clocks can reclaim a healthy lease or hold an expired one. | `core/.../connector/persistence/WorkLeases.java` | Compute both sides in SQL (`CURRENT_TIMESTAMP + INTERVAL '120' SECOND`) or inject `Clock` | behavior |
| A5 | Internal failures are swallowed without a log line: the ingestion coordinator records `SOURCE_*_INTERNAL` with no stack trace, the OAuth2 failure handler discards the exception, the invitation intake controller swallows `InvitationException`, and `api` has no logger at all. | `DefaultIngestionCoordinator`; `OAuth2LoginFailureHandler`; `ActorSessionLoginSuccessHandler`; `InvitationIntakeController` | WARN with operation id, actor id, reason code; never email, secret, or token | safe |
| A6 | Tika child process diagnostics are discarded (`Redirect.DISCARD`), the classpath fallback to `java.class.path` does not contain `TikaExtractionProcess` under the packaged layout (only the image's `MEMORYOS_EXTRACTION_CLASSPATH` makes it work), and `deleteOnExit` accumulates entries in a long-lived worker. | `connector/.../TikaSourceContentExtractor.java` | Redirect stderr into the temp directory and surface a bounded tail in the exception; log instead of `deleteOnExit`; consider a fail-closed startup self-check | behavior (self-check), safe otherwise |
| A7 | `ThemeProvider` reads and writes `localStorage` unguarded and is mounted outside the root `ErrorBoundary`; blocked storage renders a blank page. | `web/src/features/theme/theme-provider.tsx`; `web/src/main.tsx` | try/catch around storage; mount inside the boundary | behavior |
| A8 | The PostgreSQL healthcheck runs `pg_isready` over the Unix socket (`PGHOST=/var/run/postgresql`), which the image's temporary init server also serves, so the container reports healthy before the init script has created the `keycloak` and `memoryos` databases. | `infrastructure/deployment/compose.base.yaml` postgres healthcheck | `pg_isready -h 127.0.0.1 ...` (TCP only listens once the real server is up) | behavior |
| A9 | Seven of nine tracked shell scripts have mode `100644`; direct invocation on Linux fails with "Permission denied", and the postgres entrypoint sources rather than executes the init script. | `api/src/main/docker/api-entrypoint.sh`, `infrastructure/**/*.sh` | `git update-index --chmod=+x` | safe |
| A10 | Keycloak realm lacks brute-force protection, a password policy, and `sslRequired`; `memoryos-browser-client`, `memoryos-client`, and `memoryos-user-provisioner-client` omit `fullScopeAllowed: false`, so browser tokens carry every realm role including `memoryos-inspector`. | `infrastructure/keycloak/configure-memoryos-realm.sh`; the three client JSON files | Extend the realm payload; add `fullScopeAllowed: false` | behavior |
| A11 | Database and Keycloak admin passwords travel as container environment (visible in `docker inspect`, `/proc/1/environ`) and as `psql --set` argv; the staging inspection stack already uses file secrets correctly. | `compose.base.yaml`; `bootstrap-shared-databases.sh`; `provision-inspector-role.sh` | `POSTGRES_PASSWORD_FILE`, `\getenv` inside SQL, `PGPASSFILE` secret for backups, `keycloak.conf` secret | behavior |
| A12 | API and worker images start as `USER 0:0` and the launcher `su`s down to `memoryos`, which is why compose grants `DAC_OVERRIDE`, `SETGID`, `SETUID`; the only reason is reading the Infisical bootstrap file and the Redis password file as root. | `Dockerfile`; `api/src/main/docker/api-entrypoint.sh`; `api/src/main/docker/application-launcher.sh`; `compose.base.yaml` | Own the host secret file `1654:1654` mode `0400`, `USER 1654:1654`, `exec infisical run ... -- java`, drop `cap_add` | behavior |

## B. Safe changes (no observable behavior change)

### Backend

- JWKS decoder uses Spring Security's default 30 s connect/read timeouts; inject `OAuth2ResourceServerProperties` and a `RestTemplateBuilder` with 2 s / 5 s instead of the two `@Value` lookups (`SecurityConfiguration`, `TenantConfiguration`).
- JSpecify nullness is applied per class; put `@NullMarked` on each `package-info.java` and annotate the real nulls (`InvitationView`, `InvitationResponse`, `ActorSessionLoginSuccessHandler.acceptInvitation`).
- `DefaultInvitationService` binds `memoryos.invitation.time-to-live` with `@Value` and validates by hand; an `InvitationProperties` record with `@DefaultValue` matches `KeycloakAdminProperties`. `WorkerProperties.batchSize` needs `@DefaultValue("8")`, otherwise a missing key yields `0` and silently clamps to 1.
- Mockito on JDK 25 runs without a `-javaagent`; add the agent configuration and `testLogging` in the root build.
- Two dependency-management mechanisms coexist (`platform(libs.spring.boot.dependencies)` in core/connector, the dependency-management plugin in api/worker); standardize on the platform. `connector` only needs `spring-boot-autoconfigure`, not the full starter.
- `Referrer-Policy` is set by hand on two intake routes; set it once per security chain and delete the manual header.
- `JdbcIndexAttemptRepository.findMappedDocument` is `@Transactional(readOnly = true)` but always runs inside the coordinator's read-write transaction; drop the annotation.
- Logout matcher inspects `getServletPath()`; use `PathPatternRequestMatcher` plus a header matcher. `ActorSessionLoginSuccessHandler` instantiates its own `HttpSessionSecurityContextRepository`; declare one bean and inject it.
- Timestamp mapping uses legacy `java.sql.Timestamp`; use `getObject(column, OffsetDateTime.class)`.
- `SourceException.invalid` has no cause overload, so the upload `IOException` cause is dropped.
- Tests: five API `@SpringBootTest` classes each start their own identity stub on a random port, which defeats context caching; polling uses hand-rolled `parkNanos` loops where Awaitility is on the classpath; `@Testcontainers(disabledWithoutDocker = true)` silently skips every PostgreSQL-only contract when Docker is missing.
- `RedisExecutionTopology` adds and deletes a marker record before `createGroup`; Spring Data Redis `createGroup` is reported to create the stream (`MKSTREAM`), which would make the marker unnecessary. Not yet verified against the Lettuce implementation.

### Frontend

- Nested `<main>` landmarks: the sources detail panel and `RoutePending` both render `<main>` inside the shell's `main#main-content`.
- `EmptyTitle` is a `div` patched with `role="heading"`; `RouteNotFound` and `ApplicationError` have no heading; `EmptyDescription` is typed as `p` but renders `div`.
- `aria-label` on generic elements (`Brand` span, composer div); use `role="img"` plus `sr-only` text, or `role="group"`.
- The identity query hand-builds key and `queryFn` and performs a side effect inside `queryFn`; use `getCurrentIdentityOptions()` and move `acceptCurrentIdentity` into `QueryCache.onSuccess`. The disabled source-detail query uses a placeholder key; use `skipToken` or render the panel conditionally.
- `/invitation` search validation is hand-written with an unconstrained `reason`; a Zod enum with `.catch` also types the failure copy.
- `tsconfig.app.json` relies on TypeScript 7 defaults for `strict`; set it explicitly, add `noUncheckedIndexedAccess`, `DOM.Iterable`, and include the Vitest, Playwright, and openapi-ts config files.
- The sources page uses `??` and `?.` fallbacks on fields the contract marks required; delete them. Adding `enum` to the Source status/type schemas in OpenAPI would make the status tone tables exhaustive (contract change).
- `invitation-table.tsx` casts table meta; augment `TableMeta` instead.
- React 19: `SidebarTab` still uses `forwardRef`; `ThemeProvider` mirrors `matchMedia` through `useEffect` where `useSyncExternalStore` fits; several files reference the `React` namespace without an import.
- Reduced-motion handling is per element and inconsistent; one `@media (prefers-reduced-motion: reduce)` block in `base.css`.
- Two token vocabularies coexist (shadcn aliases versus `surface-*` / `content-*`); restrict the aliases to vendored `components/ui/*`.
- Tooling: `check:routes` runs a full `vite build` to regenerate `routeTree.gen.ts` (use `tsr generate`); `build` runs `tsc -b` last (fail fast by running it first); `generate:api` uses `pnpm dlx` outside the lockfile, also inside the web image build (add `@hey-api/openapi-ts` as a devDependency); `pnpm-workspace.yaml` lists `minimumReleaseAgeExclude` while `minimumReleaseAge` is unset; enable the oxlint `jsx-a11y` plugin.

### Infrastructure and delivery

- CI: actions pinned to major tags (pin to SHA), no `concurrency` cancellation, no Gradle report artifacts on failure, pnpm cache unavailable because pnpm is installed after `setup-node`.
- CI has no gates for what it ships: `docker compose config`, `shellcheck`, `hadolint`, an image scan, and `dependency-review-action` are all cheap additions.
- Image builds re-download every Gradle dependency per run; use buildx with `type=gha` cache and copy build files before sources.
- `web/nginx.conf` keeps `user nginx;` although the master runs non-root (warning on every start); no `gzip`, `object-src 'none'`, or `Permissions-Policy`.
- OCI `image.source` labels point at `github.com/dathip04/MemoryOS` while the remote is `kl3inIT/MemoryOS`; images declare no `EXPOSE` or `STOPSIGNAL`.
- No `.editorconfig`, no `org.gradle.jvmargs`, and Dependabot does not cover the digest-pinned images in the compose files.
- `KC_HOSTNAME` and `--trusted-proxy-ip` have environment-specific defaults in shared files; use `${VAR:?}`.
- `configure-memoryos-realm.sh` runs under POSIX `sh` with `set -eu`, so `kcadm get | sed` pipelines mask failures; switch to bash with `pipefail` or capture first.
- Mailpit TLS provisioning lacks the rotate flag and 30-day expiry warning that the inspection script has.
- `compose.production.yaml` selects an API `production` profile that has no file, and no production target exists; either delete the overlay or add the profile.

## C. Behavior, contract, or schema changes: decide before implementing

- **Candidate V8 migration** (all three passes converged): `index_attempts (tenant_id, connector_item_id, pair_sequence DESC)`, `index_attempts (tenant_id, connector_item_version_id)`, `connector_item_versions (tenant_id, connector_id)`, `tenant_invitations (open_email_key) WHERE open_email_key IS NOT NULL`, partial claim indexes on `status IN ('NOT_STARTED','IN_PROGRESS')`, `tenant_invitations (tenant_id, normalized_email, id)`; `document_versions.metadata_json` to `jsonb`.
- **Readiness**: the API exposes no readiness group and the initial-tenant bootstrap runs as an `ApplicationRunner` after Tomcat starts, so the compose healthcheck can report UP before bootstrap completes; add probes and point the healthcheck at `/actuator/health/readiness`. The API has no `application-production.yaml`, and neither runtime emits structured logs.
- **Validation**: `listInvitations` maps `IllegalArgumentException` to a 400 without `code`, unlike `listIndexAttempts`; `CreateInvitationRequest` has no bean validation. Aligning them changes the 400 body.
- **Keycloak provisioning inside the invitation transaction** pins a pooled connection across up to five HTTP calls; either keep the rollback semantics and size the pool, or commit first and compensate.
- **Infra**: run the Keycloak bootstrap admin once instead of as permanent environment; `cap_drop` for postgres; Redis has a cgroup limit but no `maxmemory` (OOM-kill instead of `OOM command not allowed`; use `noeviction`); backups have no retention or scheduler.
- **Frontend UX**: replace the hand-rolled `waitForCleanup` loop with `refetchInterval`; two admin tabs announce `aria-current="page"` at `/admin/invitations` because TanStack `Link` prefix-matches (`activeOptions={{ exact: true }}`); the account menu is a Popover (`role="dialog"`) rather than a menu; no focus management after navigation; mutation invalidation lives in `try/catch` after `mutateAsync` instead of `onSuccess`; the central retry policy is overridden by every query; router loaders and prefetch are unused and `AppShell` state resets between shells; the file input is not reset after upload; dark theme flashes light on first paint.

## D. Already in good shape

Digest-pinned and non-root images, `cap_drop`/`read_only`/loopback ports/`${VAR:?}` in compose, CSP without `unsafe-inline`, cookie flags, `SKIP LOCKED` claims with claim tokens, composite tenant foreign keys and CHECK constraints, Modulith boundaries, generated client with e2e stubs asserting the CSRF header, `ConfirmDialog` accessibility, Flyway defaults, version catalog and toolchain configuration.
