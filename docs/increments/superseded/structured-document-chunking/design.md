# Versioned structured document chunking

> Superseded planning draft: this scope is now part of [MEM-46 Search](../../completed/mem-46-search/design.md). The original draft below is retained for reference; its DocumentVersion/profile model and separate MEM-62 delivery are not current implementation instructions.

Status: planned; consumes Docling and Google-native canonical extraction artifacts.

Tracking: [MEM-62](https://linear.app/memory-os/issue/MEM-62), child of [MEM-60](https://linear.app/memory-os/issue/MEM-60). Depends on MEM-61 and MEM-63; blocks MEM-48.

## Outcome

Persist reproducible Tenant-scoped chunks for MEM-48 without parsing raw files again. One named structured policy dispatches by block semantics, not file suffix: heading-aware prose, row/header-aware tables, and bounded recursive splitting for oversized/unstructured text. Use current OrgMemory implementation/tests as reference after reviewing its pinned checkout; do not import its entire GraphRAG or authorization stack.

## Contract

Each chunk records Tenant, DocumentVersion, extraction/profile identity, chunk policy/tokenizer version, stable ordinal/ID, content hash, token count and exact source provenance. Preserve typed table/cell data independently of the textual representation sent for embedding. Table splitting repeats relevant headers and never silently separates values from row/column context. Define merged headers, very wide tables and oversized single cells explicitly; do not silently truncate. Native Sheet citations retain spreadsheet ID, sheet ID and range.

Pin policy before execution; retries reproduce the manifest or fail explicitly. Chunk text and manifest remain PostgreSQL-authoritative in agreement with MEM-46. Rechunking creates a new immutable chunk set; embedding-model changes reuse compatible chunks, or explicitly require a new chunk policy when token limits change. No embedding call is necessary for the first structured policy.

Integrate chunking in the real durable worker path with version/claim fencing, cancellation and cleanup; choose whether a separate generic CHUNK operation is necessary based on actual retry/atomicity boundaries, not a worker-per-job assumption. Publication never makes a retired version eligible. ACL changes update eligibility/projection evidence without rewriting chunk text unnecessarily.

Extraction-complete, chunks-ready, embeddings-ready and search-ready are distinct facts. The UI/API may use their established naming conventions but must not claim search readiness before MEM-49 confirms the intended projection generation.

## Acceptance

Golden fixtures prove deterministic text/table chunk boundaries, header preservation, token caps and source positions across FILE and native Google documents. Changed policy creates a new set; duplicate and stale execution cannot duplicate or replace the active set. Delete/revoke prevents use before async cleanup. MEM-48 receives exact persisted authorized chunk text through a capability API, not MinIO parser output or raw files.
