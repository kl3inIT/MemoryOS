# Structured extraction with Docling

Status: implementation in progress; acceptance uses the existing FILE route. Google Drive integration is owned by MEM-10 and is not a prerequisite for closing MEM-61.

Tracking: [MEM-61](https://linear.app/memory-os/issue/MEM-61), child of [MEM-60](https://linear.app/memory-os/issue/MEM-60).

## Decision and boundary

Use Docling Serve with the official Java client for PDF/DOCX/PPTX. Retain existing TXT/Markdown support. Native table readers for CSV/XLSX and provider API readers for Google Sheets/Docs belong to MEM-63. No MinerU runtime, automatic Tika fallback or dynamic document-complexity router in this slice. The Java SDK is a service client, not a JVM-native parser.

Evolve SourceContentExtractor into a provider-neutral structured result without exposing Docling SDK types to core. Keep the flat module rules: core owns capability contracts/persistence, connector hosts real extraction/provider adapters, worker composes them. Freeze exact package placement before coding; do not create a generic plugin framework or speculative module.

## Canonical output

Versioned blocks carry type, stable index, text, heading hierarchy, tables with cell/header/row semantics, and provenance where the source provides it. Retain page/bounding-box coordinates for PDFs, sheet/ranges for tables and tab/element paths for native Docs. Missing provenance stays explicitly absent, never fabricated. Image references describe artifacts, not a promise of VLM image understanding.

Store canonical JSON in private MinIO. PostgreSQL owns the stable Document's current metadata, source checksum, artifact reference and eligibility. Replace these fields in place, following Onyx's current-document model; do not retain extraction-version history or lock retry output to a processing profile. Track artifacts before publication and reclaim them once no current Document references them.

Normalized text is transient parser output, not a PostgreSQL projection. Downstream chunking reads the canonical artifact. Extraction artifact storage does not move MEM-46 chunk/embedding authority out of PostgreSQL. V11 copies current metadata/reference and discards old version history and text for dev/staging; missing legacy artifacts do not block migration. The normal FILE reindex route regenerates them when needed.

## Execution and service

Use existing INGESTION claims/leases and Redis delivery. Upload bounded bytes to the private service; do not forward Google credentials, arbitrary user URLs or broad MinIO credentials. Persist async task identity if used, bound polling, and reconcile orphaned tasks/artifacts. Fence all publication against the current operation token and eligible source/version. HTTP timeout does not prove server cancellation; late output must never publish after cancellation or deletion.

Deploy one version-pinned container, private endpoint, warmed local model cache and scratch volume. Start CPU testing with one concurrent document; 8 vCPU/32 GB RAM is a benchmark budget, not a minimum or throughput guarantee. Do not reserve the answer-model GPU implicitly. Capture actual peak RAM, latency, failures and scratch usage; configure CPU/RAM/page/output/time limits from evidence. Offline operation requires pinned model artifacts and OCR assets. Docling does not own business state, source ACLs, chunking or embeddings.

## Acceptance

Real FILE upload traverses MinIO/PostgreSQL/Redis/Docling and returns a structured Document. Drive consumes the same extraction contract in MEM-10 and is not a completion gate here. Vietnamese text and tables preserve expected values, headers and citations in a fixed fixture corpus. Malformed/encrypted/over-limit/empty/partial output is distinguishable from success. Worker/service restart, duplicate delivery, stale claims and removal races converge safely. Adopted and orphan artifact cleanup are tested. No unsupported feature appears in UI.

## Implemented choices and verification boundaries

The SDK is `ai.docling:docling-serve-client:0.6.5`; the CPU server/model image is v1.32.0 at digest `sha256:576fc2074ac77bcfbf3fe27633aa0dd89b452a170b2cd31689c8751e94d60f7a`. Synchronous FileSource carries base64 bytes. The SDK HTTP execution is bounded before JSON deserialization, with redirects disabled. No async task identity or polling subsystem is introduced.

Canonical output is one self-contained JSON artifact with embedded image data, not separate image objects. Tika remains solely for text compatibility; PDFBox is used for PDF admission, not content extraction. Models are immutable image assets. EasyOCR's writable user-network directory is redirected to `/tmp/easyocr` so read-only deployment does not fail during request initialization.

Artifact cleanup retains uncertain-write tombstones and re-deletes their keys daily until the writer has positively finished; it does not assume that a timeout cancelled a PUT. Parser configuration is diagnostic metadata only. Current-reference replacement and operation completion commit together; rollback preserves the previous reference. See [the accepted current-document correction](current-document-plan.md).

Verification evidence and remaining corpus/failure drills are tracked in `plan.md`. Capacity limits are not a throughput commitment. No staging deployment or Linear closure is implied by local test success.
