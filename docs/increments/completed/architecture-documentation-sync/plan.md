# MEM-84 — Documentation audit plan

- [x] Reconcile the team-facing Linear document set.
- [x] Inventory repository Markdown outside scratch and generated directories.
- [x] Detect exact duplicates, lifecycle collisions, broken relative links, and obsolete reference-product mentions.
- [x] Remove superseded comparison-only documents and obsolete references.
- [x] Repair lifecycle placement and relative links.
- [x] Add Mermaid views and rationale to `ARCHITECTURE.md`.
- [x] Check canonical documents for conflicting ownership or status statements.
- [x] Re-run link, duplicate, lifecycle, and forbidden-reference scans.
- [x] Run `git diff --check` and report remaining intentional history/limitations.

## Audit result — 2026-09-12

- Six team-facing Linear documents now cover index, system architecture, capability design, accepted decisions, engineering conventions, and active delivery/operations.
- Repository architecture is reduced to system-level views and links; capability detail remains in specs, evidence in test matrices, operations in runbooks, and future direction in vision.
- Six Mermaid views cover runtime, module dependencies, ingestion, Chat, identity, and deployment. The accompanying decision table records why PostgreSQL, Redis Streams, object storage, OpenSearch, Docling, Keycloak, and the modular monolith exist.
- The superseded comparison-only increment and direct obsolete product/vendor references were removed. Historical MemoryOS decisions and verification outcomes remain.
- MEM-79 remains only under `active/`; its stale completed duplicate was removed. Delivered Chat/Search/JIT/landing increments remain only under `completed/`.
- The repository-wide Markdown scan found no broken local links, exact duplicate files, lifecycle collisions, empty Markdown files, or obsolete named references in tracked documentation. `git diff --check` passes; CRLF normalization warnings on pre-existing edited files are informational.
