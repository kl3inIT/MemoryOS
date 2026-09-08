# Google Drive structured ingestion

Status: integration in progress on Linear, reconciled 2026-09-08; the Google provider is not merged into this main checkout. This document records the current delivery contract, not live acceptance of the implementation branch.

Tracking: [MEM-60](https://linear.app/memory-os/issue/MEM-60), [MEM-9](https://linear.app/memory-os/issue/MEM-9), [MEM-10](https://linear.app/memory-os/issue/MEM-10), [MEM-63](https://linear.app/memory-os/issue/MEM-63) and [MEM-76](https://linear.app/memory-os/issue/MEM-76) are In Progress with `nhuxuanviet27102004`. MEM-61 Docling is Done. MEM-62/47/48/49/50 have been absorbed into MEM-46 Search; MEM-11 owns Chat separately.

Actual Tasco source data has not been supplied. Google provider/runtime checks currently use synthetic documents; they do not prove ingestion or acceptance of real Tasco data. This does not prevent implementation or synthetic integration checks.

## Outcome

Deliver a usable Google Drive Source authorized by the connector account, including explicitly selected files/folders shared with that account. A consented credential may serve multiple independent Sources in the same Tenant; there is no singleton Google Source requirement. Google Sheets is the first exercised document type, not a separate Source provider. Retain the FILE Source and the PostgreSQL-authoritative, identifier-only Redis ingestion/cleanup path.

MEM-9 and MEM-10 remain the OAuth and synchronization/access work packages. They still ship together as a usable authorized source, never a management-only provider. This plan extends their older My-Drive-only/Tika-only scope; it does not claim either is implemented because Linear says In Progress.

## Scope and routes

| Source MIME type | Content acquisition and extraction |
| --- | --- |
| Google Sheets | Sheets API; typed cells, formatted/effective values, formulas where needed, sheet IDs/names and ranges |
| Google Docs | Docs API; all supported tabs, headings, paragraphs, lists and tables |
| Google Slides | Drive export to PPTX, then Docling |
| PDF / DOCX / PPTX | Drive download, then Docling |
| XLSX / CSV | Bounded Java table reader |
| TXT / Markdown | Bounded native text reader |

Native Workspace documents are snapshots/exports, not downloadable original binary files. Keep provider identity, acquisition time, revision/change evidence and content checksum. API calls across Sheets are not assumed to be an atomic snapshot: detect provider changes during acquisition and retry rather than publishing a known mixed revision. Preserve unsupported objects as explicit skipped/unsupported outcomes, not empty successful documents.

Support selected My Drive roots and explicitly selected shared-with-me files/folders. SPECIFIC traverses selected roots and explicitly approved linked-document IDs; GENERAL is an explicit creation-time choice limited to the credential's actual My Drive. Scope mode is immutable after creation. Shared Drives, shortcuts, group-directory expansion, write-back, push notifications, image-only upload, audio/video and archives remain outside this slice. Detect and report these boundaries; never silently skip them as a complete successful crawl. Do not infer a Shared Drive rollout from an account receiving a shared file.

MEM-76 extends selection validation/pagination and durable sync history. Its approved implementation must reconcile limits and sync-to-index attribution with MEM-9/MEM-10; the earlier 20-root cap is not a measured capacity guarantee or a final scale requirement.

## Ownership and runtime

1. API owns OAuth and source commands; Connector owns credentials, Sources, items, input revision evidence, provenance and the sync ledger.
2. Add generic SOURCE_SYNC execution to the existing dispatcher/consumer topology, not a Google-specific queue or independent scheduler.
3. Worker traverses the saved scope through a durable PostgreSQL frontier and per-page checkpoints, seeded by explicit roots and approved linked-document IDs. Do not reintroduce account-wide Changes cursor/replay as the sync contract. Only complete generations can prune missing entries. Permission reconciliation must not depend solely on content hashes or modified time.
4. Each admitted current input creates durable INGESTION work. Distinct Drive file IDs stay distinct even when bytes match. Deduplication is scoped by Tenant/source/provider identity and input evidence, never by global content hash alone.
5. Worker downloads/reads authorized content outside database transactions, writes tracked immutable raw/snapshot objects, extracts, and updates the stable current Document/reference under the current claim token. Retry and stale claims must not create a Document history ledger.
6. Document owns canonical structured extraction artifacts and processing identity; objectstorage owns physical storage mechanics. Connector removal drops provenance; unreferenced Document artifacts become eligible for fenced cleanup. Shared references are never deleted prematurely.
7. Revocation/removal immediately makes local mappings ineligible; async cleanup cannot resurrect them. Remote changes take effect when observed, with an explicit ACL freshness policy; do not promise instantaneous remote revocation.

## Authorization

OAuth authorizes the connector account, not all MemoryOS members. Do not create Drive Sources as PUBLIC by default. Preserve MEM-10's current-source-ACL and active-member checks and an authorized document read surface. Keep Drive permission IDs separate from OIDC subjects; never equate email with a verified identity binding. Unresolved groups, incomplete/stale ACLs and unbound principals deny. Bind test actors through an explicit verified identity route; document the exact supported principal types before shipping.

Maintain direct USER/DOMAIN/ANYONE semantics only where the provider evidence and application policy support them. Group expansion is deferred rather than silently replaced with a broader audience. Permission-only updates must not require re-parsing/re-embedding unchanged content. No document body, title, metadata or citation escapes before the access decision.

## Storage and processing contract

MinIO stores raw files, native snapshots and canonical extraction JSON. The current `memoryos-extraction-v1` artifact embeds image data; there is no separate image-object store by default. PostgreSQL stores current Document metadata/reference, source/item provenance, ACL authority, operations, checkpoints and bounded current chunk text. Reuse the worker write/adoption lifecycle and scoped credentials delivered by MEM-61; expand them only for an actual provider need. No browser upload receipt is fabricated for server ingestion.

Use the existing [current Document contract](../../../specs/document.md): stable Document ID, current metadata/checksum/reference, fenced publication and tracked artifact cleanup. Parser configuration is diagnostic metadata, not a retry constraint or uniqueness key. Do not recreate `document_versions`, a processing-profile ledger or PostgreSQL `normalized_text`; MEM-61 removed those models. Native Sheet/Doc provenance requires a compatible canonical-schema extension with fixture proof. Reconcile provider migrations against the actual main Flyway head when integrating; migration numbers from another branch are not reserved by this plan.

MEM-46 owns current chunk text/provenance in PostgreSQL and embedding vectors only in OpenSearch. Reuse its canonical-artifact consumer and current generation identities; no separate MEM-62 delivery or PostgreSQL vector store is required. Docling does extraction only; parsed is not search-ready. Chat remains MEM-11 with its separate acceptance gate.

## Delivery gates and references

First prove a multi-tab Sheet through the Google Drive Source, authorized read and resync/revoke path. Then prove native Docs and binary PDF/DOCX/PPTX routes. Google Slides depends on the Docling route. The full declared format scope must pass before this delivery is closed; a narrower released slice needs an explicitly updated allowlist and scope, not hidden placeholders.

Use the current Onyx reference checkout under `.tmp/onyx` for connector lifecycle, checkpoint and section conversion; its Sheets CSV export is not the chosen route. Use OrgMemory's current parser/chunker contracts and tests as reference, not its old notes or entire permission/runtime architecture. Pin the inspected commits in implementation evidence.

No new ADR yet: authoring the plan is not implementation. No claim of SDK compatibility, resource sufficiency, OCR quality or production readiness until tested.
