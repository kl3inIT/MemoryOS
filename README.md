# MemoryOS

Integration status: [PR #106](https://github.com/kl3inIT/MemoryOS/pull/106) merged the [Basic Access work](docs/increments/completed/basic-access-capabilities/plan.md), including its backend, migrations and generated API contract. See [PR verification](docs/increments/completed/basic-access-capabilities/verification.md#pr-106-ci-repair) for gate results. Deployment and live-provider/customer acceptance remain separate. Existing local databases with divergent feature migrations must not run the merged layout until deliberate history/schema reconciliation; see the migration warning below.

Direct document Search is implemented under [MEM-46](docs/increments/completed/mem-46-search/design.md). See the [Search runtime runbook](docs/runbooks/search-runtime.md) for local OpenSearch and managed embedding configuration, and [verification](docs/increments/completed/mem-46-search/verification.md) for remaining live-model/deployment acceptance. Chat is tracked separately in MEM-11.

MemoryOS is a durable personal knowledge system built as a controlled Spring Modulith monolith. External provider identities resolve to stable internal actors; each self-hosted deployment bootstraps one fixed Tenant and admits its configured owner through Keycloak browser login.

## Start here

- [Repository guide](AGENTS.md) — canonical navigation and workflow rules.
- [Architecture](ARCHITECTURE.md) — implemented system shape and runtime flows.
- [Vision](docs/vision.md) — product outcomes and principles.
- [Roadmap](docs/roadmap.md) — delivered and active increments.
- [Development runtime runbook](docs/runbooks/development-runtime.md) — runtime configuration and verification.
- [Shared PostgreSQL and Keycloak migration runbook](docs/runbooks/shared-runtime-migration.md) — backup, restore, cutover, rollback, and shared-realm verification.

Claude Code reads the same repository guide through [`CLAUDE.md`](CLAUDE.md); project rules are not duplicated.

Project skills live in `.skills/`. The entries under `.agents/skills/` (Codex),
`.claude/skills/` (Claude), and `.omp/skills/` (OMP) are committed relative
symbolic links to each shared skill. Edit the canonical files in `.skills/`.
On Windows, enable Developer Mode or use an elevated terminal, and clone with
`git -c core.symlinks=true clone <repository-url>` so Git creates actual links.

## Requirements

- JDK 25.
- Checked-in Gradle wrapper; no system Gradle installation.
- Node.js 24 with Corepack; `web/package.json` and `landing/package.json` pin pnpm.
- Docker with the Compose plugin for the hardened PostgreSQL, private MinIO, shared Keycloak, API, indexing worker, and web deployment stack.

## Modules and capabilities

| Module | Responsibility |
| --- | --- |
| `core` | Seven closed capability implementations; JPA IAM lifecycle, JDBC resource persistence, transactions, and the provider-neutral object-storage contract/S3 adapter |
| `connector` | Shared provider bundle: Google acquisition, offline Sheets/Docs snapshots, Java XLSX/CSV readers, Docling PDF/DOCX/PPTX, and bounded Apache Tika 4 TXT/Markdown |
| `api` | Spring Boot HTTP, validation, migration, and security composition root |
| `worker` | PostgreSQL-authoritative selection verification, source synchronization, extraction, Search projection, and cleanup over Redis Streams |

Current core capabilities are `iam`, `objectstorage`, `connector`, `document`, `ingestion`, `retrieval`, and `chat`. IAM combines identity, Tenant membership, invitations, Users, Groups, and authorization. Provider implementations remain outside capability packages under `connector/src/main/java/io/memoryos/provider/<provider>` except the capability-owned S3 adapter under `objectstorage.s3`. See [ARCHITECTURE.md](ARCHITECTURE.md) for enforced dependencies.

Chat is the application home, with private conversation history, streamed answers, Stop and reconnect through assistant-ui and a Vercel AI SDK transport adapter. Search remains available at `/search`. Java owns native Embabel/Spring AI background execution, persisted outcomes and bounded RAM replay. See the [Chat contract](docs/specs/chat.md).

## Build and verify

Windows:

```powershell
.\gradlew.bat clean check --no-daemon
```

Linux or macOS:

```bash
./gradlew clean check --no-daemon
```

Frontend:

```powershell
corepack enable
cd web
pnpm install --frozen-lockfile
pnpm check
pnpm test:e2e
```

The Gradle gate compiles all server modules, runs capability and HTTP integration tests, verifies Spring Modulith and ArchUnit boundaries, and starts both composition roots in tests. The frontend gate regenerates the OpenAPI client, rejects generated drift, lints without product-source warnings, checks formatting and TypeScript, runs focused tests, and creates the production bundle; Playwright exercises the observable browser states.

Public landing page (`https://vadan.app`, deployed separately; see the [landing runbook](docs/runbooks/landing.md)):

```powershell
pnpm --dir landing install --frozen-lockfile
pnpm --dir landing check
```

## Refresh the generated API contract

Spring controllers and Spring-visible request/response metadata own the browser API contract. The committed `openapi.yml` is generated from a full API test context and the web client is generated from that snapshot.

Windows:

```powershell
$env:MEMORYOS_OPENAPI_WRITE = "true"
.\gradlew.bat :api:test --tests "*OpenApiContractTest*"
Remove-Item Env:MEMORYOS_OPENAPI_WRITE
cd web
pnpm generate:api
```

Run the OpenAPI contract test again without the write flag, then run `pnpm check`. Normal runtime configuration does not expose springdoc API-doc endpoints.

The API and worker images are built from [`Dockerfile`](Dockerfile) and inject the explicitly selected Infisical environment before Spring Boot starts; the browser image is built from [`web/Dockerfile`](web/Dockerfile). Deployment is composed explicitly from [`compose.base.yaml`](infrastructure/deployment/compose.base.yaml) plus a staging or production overlay. The base owns PostgreSQL, private MinIO and its idempotent bucket/policy bootstrap, the Keycloak runtime shared with OrgMemory, API, worker, and web. Distinct file-mounted MinIO credentials constrain API to signed raw PUT/inspection and worker to raw/extracted read, write, and delete; both probe one private sentinel. Staging adds Mailpit, TLS Redis, read-only pgweb and Redis Insight, SSO proxies, and an owner-only bucket-read-only MinIO Console using native Keycloak OIDC; production adds no inspection exposure. Developer `bootRun` processes use Arconia's `development` profile for PostgreSQL/Redis but require an explicitly configured object-storage endpoint and credentials.

The staging application is available at `https://memoryos.72-62-193-33.nip.io`; Keycloak retains the matching exact HTTPS callback and `/invite/activate` action return for `memoryos-web`. The configured object-storage origin routes directly to MinIO port `9000` and must match the presigning endpoint, MinIO CORS allowlist, and web CSP. The staging-only Console uses `https://memoryos-minio.72-62-193-33.nip.io`, exact `/oauth_callback`, and the owner-only `memoryos-inspector` policy without exposing port `9001` directly.

## Current runtime behavior

API startup runs all 61 Flyway migrations through V61, transactionally bootstraps or verifies the configured Tenant UUID and owner, provisions protected Admin/Basic Groups with SYSTEM_ADMIN/SYSTEM_BASIC, and binds Arconia Web fixed Tenant context around HTTP requests. IAM lifecycle uses JPA; bounded projections, authorization locks, and Source/Document/Ingestion/Object Storage mechanics remain concrete JDBC persistence. Group grants and managed-Group scope authorize each request; Basic supplies direct Search access without Source administration. Source upload initiation returns a checksum-bound presigned PUT; finalization reauthorizes after provider inspection before adoption. Worker starts after migrated API health, carries each work record's explicit `TenantId` through fenced indexing/cleanup, and uses FILE/Docling or bounded Tika extraction. Chat runs inside the API on virtual threads with a single hosted O200K token estimator; no vendored tokenizer assets or native libraries ship in either image.

The integrated tokenizer backfill is V54, the Web search gateway constraint is V55 and V61 narrows the persisted Web intent to off/auto; main V1–V53 are preserved. Do not start this layout against a local/review database carrying the former branch-only tokenizer V37. Preserve that runtime until a separately authorized data-preserving cutover; see [migration ownership](ARCHITECTURE.md#data-ownership-and-consistency).

V14 invalidates existing Spring Sessions for the `io.memoryos.iam.ActorId` package cutover. Deploy API and worker as one coordinated version transition; existing browser users sign in again.

The merged layout contains 55 migrations: published main V1–V42 remain unchanged. Historical local V37–V42 are now V43 Basic seeding, V44 system-grant renaming, V45 derived-only Group read, V46 unified Source management, V47 scoped manager ownership and V48 ordinary-only Source associations. Model-management grants are preserved; standalone read/delete grants are revoked without promotion. Exact current filenames are listed in [Architecture](ARCHITECTURE.md#data-ownership-and-consistency).

**Database cutover warning:** an existing local database containing old feature V37–V42 cannot run this merged layout until deliberate, data-preserving Flyway-history and schema reconciliation. Older feature V18–V21 and retained `memoryos_main_review`/`memoryos_drive_review` histories are also divergent. Do not start the API/worker against them, automatically repair checksums/history, or reset user data. Use a fresh isolated database for verification or an explicitly reconciled database, and deploy matching API/worker/schema together. Historical evidence retains its original migration numbers and does not establish merge verification.

FILE retains checksum-bound browser-direct PUT and API finalization. Google credentials are independently authorized and reusable across Sources. Durable selection validation activates accepted General/Specific proposals before SOURCE_SYNC acquires tracked immutable snapshots and ordinary INGESTION reads them from MinIO. PostgreSQL remains authority for leases, revisions, exact run attribution and current Document/artifact publication. Google stays RESTRICTED and excluded from FILE PUBLIC; Google document ACLs, reader linking and a Google viewer remain unimplemented; FILE Search and generation-bound passage reads use the existing access resolver.

Source associations are ordinary-only, with no automatic Admin link. A Source records the group manager responsible for attaching it; that manager changes only the Groups they manage and may leave it attached to none, while global managers may save any selection and `SYSTEM_ADMIN` appoints or clears the manager. Direct Search/passage access requires Basic-derived `SEARCH_READ` and actor-bound current FILE eligibility, including authorized private FILE Sources; it never bypasses Drive document ACLs. Private Chat attachments remain independently owner-authorized and excluded from general Search. Account-owned Vietnamese/English language, safe localized errors and bounded read-only Chat artifacts remain implemented; see [localization](docs/specs/localization.md) and [Chat](docs/specs/chat.md).

For Google acquisition, an actor with global or eligible scoped `SOURCES_MANAGE` uploads or pastes a downloaded Google **Web application OAuth client JSON** of at most 16 KiB UTF-8 when authorizing a new credential. Replacing an existing OAuth client requires global authority throughout consent and callback; unchanged-app scoped reconnect retains ownership and all-attached-Source checks. Enable the Drive, Sheets, and Docs APIs in that app's project and configure its consent screen. There is no server-wide default Google client ID/secret. Supply `MEMORYOS_GOOGLE_DRIVE_CREDENTIAL_ENCRYPTION_KEY` (base64-encoded 32-byte AES key) and `MEMORYOS_GOOGLE_DRIVE_CREDENTIAL_KEY_VERSION` consistently to API and worker. API additionally requires `MEMORYOS_GOOGLE_DRIVE_REDIRECT_URI`, registered exactly in the uploaded app at the browser origin's `/login/oauth2/code/google-drive`; this does not replace Keycloak login. V24 requires legacy connections to reauthorize with an owner app while retaining their Source, roots and content. Keep credentials outside the repository. See the [Connector contract](docs/specs/connector.md) for authorization and revision semantics.

For SharePoint acquisition, an actor with global or eligible scoped `SOURCES_MANAGE` registers an **Entra application** (Directory and Application IDs as GUIDs) with the Microsoft Graph application permission `Sites.Read.All` and admin consent, then stores either its client secret Value or a PKCS#12 certificate of at most 16 KiB whose public half is already uploaded to that app registration. MemoryOS verifies every draft against Microsoft before storing it, keeps only the encrypted secret or private key plus the certificate and its SHA-1 thumbprint, and never stores the uploaded PKCS#12 or its password. Supply `MEMORYOS_SHAREPOINT_CREDENTIAL_ENCRYPTION_KEY` (base64-encoded 32-byte AES key) and `MEMORYOS_SHAREPOINT_CREDENTIAL_KEY_VERSION` consistently to API and worker; a missing key fails SharePoint alone and leaves FILE and Drive untouched. Keep credentials outside the repository.

Google setup follows Credential → Connector: select/authorize a credential, then name the Source and choose immutable scope. **Specific** defaults to at most 1,000 nonoverlapping explicit file/folder roots under backend policy, with a separate 3 MiB request budget and 500 linked approvals; these are configured bounds, not live Google capacity proof. **General** synchronizes the connected account's actual My Drive tree, not Shared with me, Shared Drives or other accounts. Both create and selection save return accepted validation; the active selection changes only after fenced worker verification. Reuse needs no additional JSON/consent. App/redirect instructions stay collapsed. Reconnect/revoke fences all attached Sources and pending selections; Source deletion retains the credential.

Specific detail presents **Selected content** as an expandable folder → actual files → recorded linked documents tree. Search/type filters retain the paged, file-ID-unique projection. Repeated targets share one draft approval and retain grouped reference locations; no technical paths or persistent selection-count subtitle are displayed. **Discover linked documents** does not approve targets. **Select for sync** loads the complete draft with that target selected and shows **Save selection / Cancel** below the tree without opening the root-links disclosure or editor. Only Save submits the full links/approvals for durable validation. **File and folder links**, below the tree, contains saved links and **Edit selection**, visible only when expanded. Explicit Edit opens the root editor and preserves existing draft approvals. Cancel discards local edits without a write; leaving after acceptance does not cancel worker execution. Active selection and pending validation remain separate, with receipt recovery. Approved files reuse the existing synchronization pipeline without new folder or reader authority. See the [Connector contract](docs/specs/connector.md).

Approved linked documents expose **Deselect for sync** instead of losing their action. Deselect changes only that approval in the draft; **Save selection** submits the change, while **Cancel** keeps the saved selection. Documents already included by selected roots remain controlled by those roots.

**Files** is the current corpus, loaded independently in bounded pages: 25 items by default, at most 100, with stable Previous/Next navigation. Rows show file, size, status, Last indexed and actions without repeated subtitles. Last indexed is a plain timestamp for successful processing completion of the current version, not upload time; there is no details disclosure. Upload returns to the newest page; reindex/removal continue observing their exact operations across page changes. See the [Source read contract](docs/specs/connector.md#source-summaries-and-files-pages).

Global managers and concretely authorized scoped Source managers configure each Google Source under **Source summary → Automatic interval → Edit**. The default is 5 minutes, with a minimum of 1 whole minute. Saving changes future scheduling without interrupting current work or changing the saved scope; Synchronize now remains available when projected actions permit it. Deep roots/approvals/discovery configuration remains global-only. See the [Connector contract](docs/specs/connector.md) for pagination, concurrency and authorization semantics.

For Google Drive, **Indexing attempts** is a compact per-Source execution table: real Started, Outcome, Checked, Indexed, Unchanged, Completed/duration and Errors. It uses retained run records rather than current document totals or inferred New/Total counters. FILE Sources retain separately named **File indexing attempts** with filenames and processing timestamps. Both histories use real Tenant/Source-scoped `totalItems` and the shared `TablePagination` with Users/Groups: `current / total`, Rows and text Previous/Next controls, 5 rows by default and 5/10/25/50-row cursor pages. Run totals also respect status, trigger and time filters, independently of the cursor. Acquisition and owned indexing remain distinct; unknown legacy counts remain Unknown. Detail/summary retention is 14/90 days with protected live/current references. Historical verification and capacity boundaries are recorded in the [active increment](docs/increments/active/google-drive-structured-ingestion/plan.md#mem-76-verification--2026-09-08); controlled fixtures are not live Google proof or production SLOs.

FILE uploads and Google binary acquisition share a 100 MiB (104,857,600-byte) admission ceiling, including Slides exports and offline binary link reading; native snapshots have a separate 32 MiB bound and explicit structural/request/time limits. Lower configured Google provider limits remain allowed. `INDEXED` means a current extraction artifact, not Search readiness. Item `searchStatus` separately reports the downstream chunk/embedding/projection state. For real Docling coverage in the Gradle worker integration suite, set `DOCLING_TEST_ENDPOINT` to a reachable pinned Docling service before running the normal gate. Remote Docling and its ingress must independently admit the input and its base64 request body; see the [parser runbook](docs/runbooks/docling-extraction.md).

Docling defaults remain the pinned upstream CPU image with EasyOCR `vi,en`. For Vietnamese scans, the optional [derived Tesseract image and Compose configuration](docs/specs/ingestion.md#docling-deployment-environment) add the Vietnamese language pack and require explicit image, engine, language and image-revision selection; there is no automatic fallback. OCR financial figures still require checking against the source PDF; this setup is not whole-corpus or deployed-service verification.

| Endpoint | Access | Result |
| --- | --- | --- |
| `GET /actuator/health` | Public | API health |
| `GET /api/identity/me` | Bound bearer JWT or authenticated browser session | Stable actor, account `uiLanguage`, nullable Tenant, global/scoped capabilities, and authorization revision |
| `GET /api/identity/me` | Missing/invalid authentication or unknown binding | `401` |
| `GET /` | Browser origin | MemoryOS application; resolves session through `/api/identity/me` |
| `GET /access-not-provisioned` | Browser origin | Accessible denial state without account creation |
| `GET /invite/activate` | Public Keycloak action return | Starts browser OAuth2 login without carrying invitation correlation |
| `/api/users` and membership/invitation commands | Global `USERS_MANAGE` | Bounded Users directory, profile/account classification, invitation and membership lifecycle |
| `POST /api/users/{actorId}/groups` | `SYSTEM_ADMIN` | Replace ordinary memberships while preserving system edges and retained manager flags |
| `/api/groups/**` | Applicable global IAM capability or own managed-Group scope | Group/member projections and explicitly authorized lifecycle, grant, and manager commands |
| `/api/sources/**` | Applicable global Source capability or concrete scoped authority | Filtered reads, scoped creation/operations/ordinary associations; global-only deep configuration and shared deletion, with the narrow creator/groupless cleanup rule in the Connector matrix |
| `/api/source-operations/**` | Global `SOURCES_READ` or associated managed-Group scope | Poll authorized durable indexing, selection-validation, source-sync and cleanup operations; dedicated Source run endpoints retain bounded history |
| `/api/credentials/google-drive/**` | Global `SOURCES_MANAGE` or eligible owned/scoped credential authority | Reusable credential lifecycle; existing OAuth app replacement is global-only, rechecked through callback |
| `/api/sources/google-drive/**` and Source Google configuration commands | Global Source management or concrete scoped authority for eligible creation/operations | Global-only existing selection/discovery; scoped sync/schedule/pause use projected actions and current resource policy |

The [identity](docs/specs/identity.md), [tenant](docs/specs/tenant.md), [invitation](docs/specs/invitation.md), [object storage](docs/specs/object-storage.md), [connector](docs/specs/connector.md), [document](docs/specs/document.md), and [ingestion](docs/specs/ingestion.md) contracts document IAM lifecycles and resource boundaries. [Search](docs/specs/search.md) defines the `retrieval` capability; identity, Tenant and invitation are IAM concerns, not separate modules.

## Engineering policies

- [Engineering conventions](docs/conventions.md)
- [Repository operating model](docs/guidelines/operating-model.md)
- [Production-first persistence](docs/guidelines/persistence.md)
- [Testing and verification](docs/guidelines/testing.md)
- [CI and staging delivery](docs/runbooks/ci-cd.md)
- [ADR 0003: evidence-driven audit boundary](docs/decisions/0003-defer-audit-until-evidence-consumer.md)

The legacy OrgMemory repository is reference-only. Do not copy its structure or infrastructure breadth without a current MemoryOS capability requirement.
