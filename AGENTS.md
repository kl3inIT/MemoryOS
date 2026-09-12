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
- Keep `core` limited to implemented capabilities. Current modules are `iam`, `objectstorage`, `connector`, `document`, `ingestion`, `retrieval`, and `chat`; IAM owns identity, Tenant membership, invitations, Users, Groups, and authorization. The shared `connector` Gradle integration bundle is organized by provider folders. Never predeclare empty future capability or provider packages.
- Start non-trivial work with an increment directory containing `design.md` and `plan.md`. Update both as scope changes.
- Preserve accepted MemoryOS contracts and scope while delivering production quality from the start. Scope control must not remove necessary hardening. Before proposing a departure, apply [reference-based design and scope control](docs/conventions.md#reference-based-design-and-scope-control) and the relevant capability spec. Do not turn comparative research or speculative improvements into requirements.
- Record an ADR only after the decision is accepted and implementation has started. ADRs are append-only; supersede them with a new ADR.
- After verification, consolidate durable facts into architecture/spec/test/guideline documents in the same change. Keep the increment under `active/` until the pull request merges; then move it to `completed/` and reconcile the roadmap.
- Never ship a temporary runtime mode, one-shot application profile, speculative endpoint, or unused abstraction to make an incomplete flow operable. Implement the real authorized runtime path, or keep the capability absent. See [ADR 0002](docs/decisions/0002-no-speculative-operational-surfaces.md).
- Test observable contracts at the narrowest useful boundary, then exercise the changed runtime surface. See [testing guidelines](docs/guidelines/testing.md).
- Use the checked-in Gradle wrapper. `clean check` is the repository-wide gate.

## Current active increments

- [Tasco scanned-PDF OCR](docs/increments/active/tasco-scanned-pdf-ocr/design.md) owns OCR, extraction and indexing of the supplied financial reports, verified through Orca Sources; Search and Chat changes are excluded.
- [Docling timeout configuration](docs/increments/active/docling-timeout-configuration/design.md) owns the bounded external parser timeout configuration.
- [MEM-60 — Google Drive ingestion](docs/increments/active/google-drive-structured-ingestion/design.md) coordinates the still-active MEM-9/MEM-10/MEM-60/MEM-63 provider and acceptance scope. MEM-76 is Done and the implementation is merged; live-provider acceptance remains open.
- [MEM-58 — Frontend observability](docs/increments/active/mem-58-frontend-observability/design.md) owns optional browser error monitoring and trace correlation.
- [MEM-77 — Provider/model administration](docs/increments/active/mem-77-provider-backend/design.md) retains the catalog administration UI and local OpenAI-compatible provider work. Its backend foundation is already implemented.
- [MEM-79 — Standalone OCR](docs/increments/active/mem-79-rancher-ocr/design.md) remains active through Worker integration and full indexing acceptance.
- [Staging deployment simplification](docs/increments/active/staging-deploy-simplification/design.md) owns the health-verified deployment boundary and explicit owner acceptance handoff.
- [MEM-84 — Architecture documentation sync](docs/increments/active/architecture-documentation-sync/design.md) owns the current Linear and repository documentation audit.

Delivered increments are under [completed](docs/increments/completed/); replaced research drafts are under [superseded](docs/increments/superseded/). The [roadmap](docs/roadmap.md) distinguishes the completed MEM-75 selected batch from the wider dependency issue, which remains open.

Keep each increment's design, plan, verification evidence, and Linear scope aligned while implementation is in flight.

## Canonical references

- [Chat session contract](docs/specs/chat.md)
- [Chat provider/model catalog](docs/specs/chat-models.md) and [backend adapter handoff](docs/increments/active/mem-77-provider-backend/adapter-handoff.md)
- Provider endpoint review must preserve the [accepted internal HTTP and trusted model-manager policy](docs/specs/chat-models.md#credentials-and-provider-extension).
- [Chat verification matrix](docs/tests/chat.md)
- [Vision](docs/vision.md)
- [Architecture](ARCHITECTURE.md)
- [Roadmap](docs/roadmap.md)
- [MEM-84 architecture audit](https://linear.app/memory-os/issue/MEM-84)
- [Conventions](docs/conventions.md)
- [Observability conventions](docs/guidelines/observability.md)
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
- [Identity and IAM authorization contract](docs/specs/identity.md)
- [Identity and IAM verification matrix](docs/tests/identity.md)
- [Tenant contract](docs/specs/tenant.md)
- [Tenant verification matrix](docs/tests/tenant.md)
- [Invitation contract](docs/specs/invitation.md)
- [Invitation verification matrix](docs/tests/invitation.md)
