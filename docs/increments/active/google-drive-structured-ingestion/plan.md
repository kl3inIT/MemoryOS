# Google Drive delivery plan

Status: integration in progress on Linear; reconciled 2026-09-08. MEM-9/MEM-10/MEM-63/MEM-76 are assigned to `nhuxuanviet27102004`. Unchecked rows are remaining integration/acceptance gates, not assertions that no branch implementation exists. Google is not yet merged into this main checkout.

Tracking: [MEM-60](https://linear.app/memory-os/issue/MEM-60). Reader review on 2026-09-06 found no blocking contradictions in the proposed scope, ownership, versioning, ACL or cleanup; this is a planning review, not implementation verification.

## Work packages

- [ ] MEM-9: OAuth, encrypted refresh-token lifecycle, shared-with-me selection and source-management boundary. Validate exact scopes, state/PKCE, callback replay, session ownership, refresh races and local revoke.
- [ ] MEM-10: SOURCE_SYNC ledger, saved-scope frontier/checkpoints, current input evidence, current ACL and authorized read. Keep the combined MEM-9/MEM-10 usable-source merge boundary; no account-wide Changes replay.
- [ ] MEM-63: Sheets API and Docs API adapters, Java XLSX/CSV/text readers and stable snapshot/provenance contract. See [native readers](native-readers.md).
- [x] MEM-61 foundation: current Document, canonical artifact lifecycle and PDF/Office parser service delivered through FILE. [Docling plan](../../completed/docling-structured-extraction/plan.md); provider reuse and acceptance remain MEM-10.
- [ ] MEM-76: selection validation/pagination and durable sync history, including accurate sync-to-index outcomes; keep its approved extension aligned with MEM-9/MEM-10.
- [ ] Integrate native artifacts with existing [MEM-46 Search](../mem-46-search/plan.md) where needed. MEM-62/47/48/49/50 are Duplicate into Search, not separate work packages; downstream Search/Chat completion does not gate provider ingestion acceptance.

## Implementation order

1. Reuse current Document/artifact interfaces delivered by MEM-61 and define the compatible native-provenance extension. Resolve SDK/server versions, module placement, acquisition consistency and ACL principal policy before runtime changes.
2. Implement MEM-9 OAuth and selected scope behind the real combined delivery, including a shared file not owned by the OAuth account. Do not expose an inert provider tile.
3. Integrate MEM-10 generic SOURCE_SYNC execution and fail-closed read path. Prove saved-scope traversal, approved linked documents, partial failures and durable checkpoint resume using recorded Google responses.
4. Implement native Sheets/Docs snapshot adapters; complete the first multi-tab Sheets vertical slice through the existing Redis worker, object storage and PostgreSQL.
5. Reuse the delivered Docling FILE extraction path for Drive PDF/DOCX/PPTX and Slides exports. Add only formats proved by fixtures and real service tests.
6. Finish cross-format deletion/revoke/credential failures and owner/member UI. Retain existing FILE regressions and no extra upload wizard steps.
7. Connect compatible current artifacts to MEM-46's existing chunking/indexing pipeline without duplicating its state or embedding store; search-ready waits for projection confirmation. Complete MEM-76's selection/history behavior under its own integration gates.
8. Run repository gates, review, merge and staging validation as separate steps. Never describe a planning write or local mock test as a deployed capability.

## Required verification

| Area | Evidence required before completion |
| --- | --- |
| OAuth | Replay/state/PKCE/missing scope, refresh race, reauth/revoke and no token leakage |
| Discovery | Shared file and shared folder, paging, selected-scope change, unsupported shortcut/Shared Drive feedback |
| Sheets | At least two tabs, merged/multirow headers, blanks, numeric/date/currency formatting, formulas vs effective values, cell/range citations and concurrent modification detection |
| Docs and files | Heading/table provenance; exported Slides; scan and text PDFs; malformed, encrypted and over-limit files |
| Sync | Restart at each checkpoint, saved-scope/approval change races, duplicate/out-of-order delivery, Redis loss, 429/5xx and partial crawl without false deletion |
| ACL | Allowed/denied member, unresolved principal/group, stale/incomplete ACL, permission-only update, revoke and retained old-version/citation access |
| Storage | Worker writes with least privilege, checksum verification, orphan cleanup, cross-Tenant rejection and delete racing extraction |
| UI | Configure Google Drive, consent/return, selected scope, sync status/errors, reauth/revoke; parsed versus search-ready distinction |
| Release | `gradlew clean check`, frontend gates when changed, real Docling container, sanitized real Google-account proof, exact-SHA staging smoke |

## Delivery artifacts

Update architecture and connector/document/ingestion/object-storage spec/test pairs in substantive implementation changes. Record API/OpenAPI regeneration, migrations, deployment limits and model-cache setup. Maintain a verification ledger with test command, SHA, result and remaining gaps. No ADR until the accepted decision enters implementation; no standalone post-merge docs PR.

## Open implementation decisions

- Exact selected-root/file limits and API scopes, grounded in the supported read routes.
- Verified user identity mapping and ACL freshness interval; no implicit email trust.
- Reuse the pinned Docling runtime; measure provider-specific capacity only where needed.
- Native snapshot consistency/retry budget and current artifact schema/migration compatibility.
- Readiness and shutdown policy when Docling is unavailable; never report success or block unrelated native routes indefinitely.

These are implementation gates, not permission to expand scope. Actual Tasco data has not been supplied. Real Google API/runtime checks may use synthetic documents; actual Tasco data acceptance remains separate.
