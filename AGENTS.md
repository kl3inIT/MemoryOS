# MemoryOS repository guide

The repository is the system of record. Chat, Linear, pull-request comments, and agent memory are inputs; durable project knowledge belongs in the documents below.

## Read in this order

1. [README.md](README.md) — build and entry points.
2. [ARCHITECTURE.md](ARCHITECTURE.md) — current implemented structure and runtime flows.
3. [docs/roadmap.md](docs/roadmap.md) — delivered, active, and candidate increments.
4. The relevant capability spec under `docs/specs/` and verification matrix under `docs/tests/`.
5. The active increment under `docs/increments/active/` when changing in-flight work.
6. Accepted decisions under `docs/decisions/` only when their rationale matters.

## Operating rules

- Keep this file a map, not an encyclopedia. Put each fact in one canonical document and link to it.
- Before building a component, follow [component and library reuse](docs/conventions.md#component-and-library-reuse): always inspect and reuse suitable library components, hooks and behavior before writing custom equivalents.
- Classify knowledge before writing: current implementation in `ARCHITECTURE.md` or `docs/specs/`; product intent in `docs/vision.md`; cross-cutting engineering policy in `docs/conventions.md` or `docs/guidelines/`; change-local reasoning in the active increment.
- Treat `core` as capability implementation, not a framework-free domain layer. Capability code may use Spring, `JdbcClient`, transactions, or JPA when they reduce real complexity; forbid dependency inversion violations and speculative layers, not framework use.
- Keep SQL, row mapping, locks, claims, and bulk persistence mechanics in concrete capability `persistence` repositories. Application services own authorization, validation, orchestration, and cross-repository transaction boundaries; do not add single-implementation repository interfaces. See [persistence policy](docs/guidelines/persistence.md).
- Keep `core` limited to implemented capabilities. Current modules are `iam`, `objectstorage`, `connector`, `document`, `ingestion`, `retrieval`, `chat`, `library` (a person's uploads, the file library, trash, the storage limit, thumbnails and archives; Chat, meetings and ingestion depend on it), `ai` (the model catalog, provider adapters and single model calls such as meeting minutes), `voice` (voice connections, transcription and synthesis), `mcp`, `usage` (AI usage and costs), `meeting` (owner-private meetings), `audit` (the Tenant's audit stream, below every capability that records into it), and `shared` (the shared kernel of identifier types, `TenantId` and `ActorId`, that every module may depend on); IAM owns identity, Tenant membership, invitations, Users, Groups, and authorization. The shared `sources` Gradle integration bundle holds the provider adapters under `io.memoryos.connector.adapter.<provider>` and content extraction under `io.memoryos.ingestion.extraction` ([ADR 0016](docs/decisions/0016-integration-bundle-named-sources.md)). Never predeclare empty future capability or provider packages.
- Start non-trivial work with an increment directory containing `design.md` and `plan.md`. Update both as scope changes.
- Preserve accepted MemoryOS contracts and scope while delivering production quality from the start. Scope control must not remove necessary hardening. Before proposing a departure, apply [reference-based design and scope control](docs/conventions.md#reference-based-design-and-scope-control) and the relevant capability spec. Do not turn comparative research or speculative improvements into requirements.
- Record an ADR only after the decision is accepted and implementation has started. ADRs are append-only; supersede them with a new ADR.
- After verification, consolidate durable facts into architecture/spec/test/guideline documents in the same change. Keep the increment under `active/` until the pull request merges; then move it to `completed/` and reconcile the roadmap.
- Never ship a temporary runtime mode, one-shot application profile, speculative endpoint, or unused abstraction to make an incomplete flow operable. Implement the real authorized runtime path, or keep the capability absent. See [ADR 0002](docs/decisions/0002-no-speculative-operational-surfaces.md).
- Test observable contracts at the narrowest useful boundary, then exercise the changed runtime surface. See [testing guidelines](docs/guidelines/testing.md).
- Use the checked-in Gradle wrapper. `clean check` is the repository-wide gate.

## Current active increments

- [Tasco scanned-PDF OCR](docs/increments/active/tasco-scanned-pdf-ocr/design.md) owns OCR, extraction and indexing of the supplied financial reports, verified through Orca Sources; Search and Chat changes are excluded.
- [MEM-60 — Google Drive ingestion](docs/increments/active/google-drive-structured-ingestion/design.md) coordinates the remaining live-provider and customer-data acceptance. MEM-9, MEM-10, MEM-63 and MEM-76 are Done and the implementation is merged; selection edits preserve retained indexes since PR #296.
- [MEM-58 — Frontend observability](docs/increments/active/mem-58-frontend-observability/design.md) owns optional browser error monitoring and trace correlation.
- [MEM-26 — Chat reply stream replay in Redis](docs/increments/active/mem-26-chat-stream-redis/design.md) owns moving the Chat reply replay buffer to Redis, the Onyx-style resume endpoint behavior and the browser recovery rule; several API replicas are excluded.
- [Time filters and documents without dates](docs/increments/active/undated-documents-time-filters/design.md) owns the rule that a missing source date does not remove a document from a Chat time filter, and the single retry when an inferred window returns nothing. Capturing Google Drive source dates during ingestion is follow-up work.
- [Chat limits belong to the person](docs/increments/active/chat-limits-for-the-person/design.md) owns the move of the file-library ceiling into deployment configuration, the person's own storage page and retention window, and the tombstone that keeps a purged image or generated file explained on its answer.
- [Library image thumbnails](docs/increments/active/library-image-thumbnails/design.md) owns the derived thumbnail served to the file library and the owner-private caching of generated-image bytes; thumbnails for uploaded images and presigned storage URLs are excluded.
- [Document viewer](docs/increments/active/document-viewer/design.md) owns serving the stored original of any media type, the shared preview kit both Chat and Search render through, locating a cited passage inside a rendered original, and the reading layout. An unlocated citation is never drawn. PPTX rendering, ingestion-recorded anchors and a standalone viewer route are excluded. The backend routes, the shared kit, the citation locator and the reading layout are implemented; live acceptance against the Tasco corpus remains open.
- [File detail actions in the library](docs/increments/active/library-file-detail-actions/design.md) owns asking Chat about the file the preview is showing, cropping an image into a new library file, and the page-size control on the library's pagination; editing an image with a model and server-side image editing are excluded.
- [MEM-135 — Embedding settings](docs/increments/active/mem-135-embedding-settings/design.md) owns choosing the embedding provider and model on an administration page, rebuilding the index in the background from stored chunks while the current one keeps serving, and moving production to self-hosted Qwen3-Embedding-4B on the `serving` node GPU; instant switchover and per-Tenant models are excluded. Implemented; staging and production have searched with Qwen3-Embedding-4B since 2026-09-25, and the OpenAI generations are retained until 2026-10-02.
- [MEM-141 — RAG benchmark](docs/increments/active/mem-141-rag-benchmark/design.md) owns the retrieval and answer benchmark under `tools/rag-benchmark`: a frozen corpus, seven question categories including Group authority, and the zero-regression gate.
- [MEM-77 — Provider/model administration](docs/increments/active/mem-77-provider-backend/design.md) retains the catalog administration UI and local OpenAI-compatible provider work. Its backend foundation is already implemented.
- [MEM-79 — Standalone OCR](docs/increments/active/mem-79-rancher-ocr/design.md) remains active through Worker integration and full indexing acceptance.
- [MEM-90 — Google Drive service-account credentials](docs/increments/active/google-drive-service-account/design.md) owns the additive service-account credential type with domain-wide delegation. The credential and Google Group membership for Auto Sync are implemented; whole-domain traversal and live Workspace acceptance remain open.
- [MEM-92 — Meeting notes (Cuộc họp)](docs/increments/active/meeting-notes/design.md) owns Glean-style bot-less meeting capture on the web: microphone and tab audio as two tracks relayed to the Tenant's voice connection (existing providers plus new Soniox), utterances stored without audio, minutes written by the API from the transcript, a Vietnamese biên bản exported to Word, owner-private notes published to the file library, and a new `Cuộc họp` menu; cross-meeting tasks and the desktop app are later phases; transcript connectors are MEM-169. Recording, uploading a recording, transcription, the minutes job, the biên bản export, sharing and publishing to the library are implemented; the Tenant setting (MEM-178) and live Soniox acceptance remain open.
- [Chat Web search](docs/increments/active/chat-web-search/design.md) owns external Web search/URL reading; native provider-hosted adapters and live-provider acceptance remain open.
- [MEM-91 — Chat voice](docs/increments/active/mem-91-chat-voice/design.md) owns Onyx Voice parity: Tenant voice providers and `/admin/voice`, streaming dictation in Chat and Search, read-aloud, auto-send/auto-playback/auto-listen and user voice settings; audio is never stored.
- [MEM-112 — Chat MCP client](docs/increments/active/mem-112-chat-mcp-client/design.md) ports the Onyx MCP client to Chat without reducing its behavior (transports, authentication types, discovery/DCR, admin tool enablement), adds several OAuth clients per server, and is accepted against Google Drive MCP; MemoryOS as an MCP server and write-tool approval are excluded.
- [Sign-out without the Keycloak logout page](docs/increments/active/logout-without-keycloak-page/design.md) owns ending the Keycloak session by its `sid` through the admin API during application sign-out, with the provider logout page as fallback, and upstream (Tasco) logout through Keycloak back-channel logout on managed identity providers.
- [Source manager owns Group attachment](docs/increments/active/source-manager-group-authority/design.md) owns the recorded Source manager, the Group-scoped detach command, administrator appointment and the unattached-Source warnings.
- [MEM-126 — SharePoint connector](docs/increments/active/sharepoint-connector/design.md) owns the SharePoint Online Source ported from the Onyx connector: Entra app credentials (client secret or certificate), site/library/folder scope, Onyx-style timestamp refresh with scheduled pruning, site pages, Auto Sync of SharePoint permissions like Google Drive (Q13) and the shadcn Sources surfaces.
- [MEM-192 — OCR on the serving GPU](docs/increments/active/mem-192-ocr-gpu/design.md) owns reading scanned PDFs and images with PaddleOCR-VL-1.6 on the `serving` node RTX 4090, one MemoryOS Document model (`memoryos-extraction-v2`) that every provider adapter writes, Docling without OCR for files with a text layer, and the shared `compose.serving.yaml` rolled out by the production workflow. Implemented and running on production since 2026-09-23 and on staging through `ocr.vadan.app`; the Tasco-corpus acceptance and the browser highlight check remain open. Chat models on the GPU are MEM-193.
- [MEM-171 — Production deployment](docs/increments/active/mem-171-production-deployment/design.md) owns the production delivery path onto the customer's two bare Ubuntu 24.04 nodes (`hn-fci-k8s-aioffice`, Docker Compose, not Kubernetes): parametrizing the staging script and workflow by environment instead of forking them, completing the production Compose overlay and the missing API `production` profile, and the manually dispatched first promotion. The first promotion ran on 2026-09-22 and the node serves `app.vadan.app`; Docling and the second node remain open.
- [Server secrets are files on the server](docs/increments/active/server-secrets-as-files/design.md) owns moving every server secret out of the vault and into files generated on the machine, leaving Infisical to developer laptops; the entry point stops calling Infisical and the launcher reads any `<NAME>_FILE`. Implemented: staging and production both read every secret from files, and the staging bootstrap credential is the only cleanup left.
- [MEM-191 — Extraction falls back to the native reader](docs/increments/active/mem-191-extraction-fallback/design.md) owns reading PDF/DOCX/PPTX with the bounded Tika child when Docling fails for a reason of its own, refusing a blank or scan-thin result, and recording the fallback in document metadata; runtime OCR configuration and several OCR providers are excluded.
- [Group detail deferred save](docs/increments/active/group-detail-deferred-save/design.md) owns the browser-local draft on the Group detail page: membership, manager, capability, name and Source-association edits commit only through the page-level Save Changes action; Cancel discards every draft.
- [Project icons and page polish](docs/increments/active/project-icons-and-page-polish/design.md) owns the persisted project topic icon (V123, Persona icon vocabulary), the five-at-a-time project conversation list, the collapsible project files group and the sidebar focus-ring removal.
- [Phase 4 — conventions and API contract hygiene](docs/increments/active/phase4-conventions/design.md) owns imports and fluent logging everywhere, the response headers and `contract/` records, the typed `ApiProblem` extensions and OpenAPI customizer, typed Chat failure codes, the identity people-and-Group search, moving the Google Drive account client and sign-in admission out of `api`, and the `deploy.sh` ERR trap.
- [Embabel 1.5.2 and the reuse audit](docs/increments/active/embabel-1.5.2-audit/design.md) owns the Embabel bump, the always-dated Chat prompt (the per-agent date switch is removed, V131), and `ModelCalls` retrying an unbound reply with the instructions as the system message.
- [Audit quality fixes](docs/increments/active/audit-quality-fixes/design.md) owns the defects and local fixes from the 2026-09-24 whole-repository quality audit that need no owner decision; the rest waits in [owner-decisions.md](docs/increments/active/audit-quality-fixes/owner-decisions.md).
- [MEM-66 — AI gateway and CPU vLLM research](docs/increments/active/mem-66-vllm-cpu-gateway-research/design.md) is parked: self-hosted model serving was removed on 2026-09-19 until a qualified environment exists.

Delivered increments are under [completed](docs/increments/completed/); replaced research drafts are under [superseded](docs/increments/superseded/). The [roadmap](docs/roadmap.md) distinguishes the completed MEM-75 selected batch from the wider dependency issue, which remains open.
Keep each increment's design, plan, verification evidence, and Linear scope aligned while implementation is in flight.

## Canonical references

- [Chat session contract](docs/specs/chat.md)
- [Chat provider/model catalog](docs/specs/chat-models.md) and [backend adapter handoff](docs/increments/active/mem-77-provider-backend/adapter-handoff.md)
- Provider endpoint review must preserve the [accepted internal HTTP and trusted model-manager policy](docs/specs/chat-models.md#credentials-and-provider-extension).
- [Chat verification matrix](docs/tests/chat.md)
- [File library trash decision](docs/decisions/0014-file-library-trash.md)
- [Capability module map decision](docs/decisions/0015-capability-module-map.md)
- [Integration bundle named `sources` decision](docs/decisions/0016-integration-bundle-named-sources.md)
- [Shared kernel holds technical utilities decision](docs/decisions/0017-shared-kernel-holds-technical-utilities.md)
- [AI usage and costs contract](docs/specs/ai-usage.md) and [verification matrix](docs/tests/ai-usage.md)
- [Vision](docs/vision.md)
- [Architecture](ARCHITECTURE.md)
- [Roadmap](docs/roadmap.md)
- [MEM-84 architecture audit](https://linear.app/memory-os/issue/MEM-84)
- [Conventions](docs/conventions.md)
- [Observability conventions](docs/guidelines/observability.md)
- [Colour and design tokens](docs/guidelines/design-tokens.md)
- [MCP server runbook](docs/runbooks/mcp-servers.md)
- [Operating model](docs/guidelines/operating-model.md)
- [Persistence policy](docs/guidelines/persistence.md)
- [Shared connector and JDBC source persistence decision](docs/decisions/0006-shared-connector-bundle-and-jdbc-source-persistence.md)
- [Unified JPA IAM and Group authorization decision](docs/decisions/0007-unified-jpa-iam-and-group-authorization.md)
- [Object storage contract](docs/specs/object-storage.md)
- [Object storage verification matrix](docs/tests/object-storage.md)
- [Connector contract](docs/specs/connector.md)
- [Connector verification matrix](docs/tests/connector.md)
- [Document contract](docs/specs/document.md)
- [Document verification matrix](docs/tests/document.md)
- [Ingestion contract](docs/specs/ingestion.md)
- [Ingestion verification matrix](docs/tests/ingestion.md)
- [Keycloak invitation provisioning decision](docs/decisions/0005-keycloak-invited-user-provisioning.md)
- [Shared identity runtime decision](docs/decisions/0004-memoryos-owned-shared-identity-runtime.md)
- [Shared runtime migration runbook](docs/runbooks/shared-runtime-migration.md)
- [Meetings contract](docs/specs/meeting.md) and [verification matrix](docs/tests/meeting.md)
- [Audit evidence contract](docs/specs/audit.md) and [verification matrix](docs/tests/audit.md)
- [Identity and IAM authorization contract](docs/specs/identity.md)
- [Identity and IAM verification matrix](docs/tests/identity.md)
- [Tenant contract](docs/specs/tenant.md)
- [Tenant verification matrix](docs/tests/tenant.md)
- [Invitation contract](docs/specs/invitation.md)
- [Invitation verification matrix](docs/tests/invitation.md)
