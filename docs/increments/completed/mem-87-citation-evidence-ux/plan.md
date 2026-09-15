# Plan

## 0. Scope

- [x] Owner confirmed Chat + Search, Drive deep links and PDF page view (2026-09-14); MEM-87 title/body updated.
- [x] Glean documentation and Onyx source comparison recorded in design.
- [ ] Before/after screenshots (desktop, mobile) from the e2e fixture server; attach to MEM-87.

## 1. Presentation metadata

- [x] `ChatSource` `mediaType`, `sourceTypes`, `providerUrl`; populated by SearchTool, FileReaderTool, turn setup and image inputs.
- [x] `DocumentSourceMetadata.providerFileId` + `providerUrl()`; not indexed.
- [x] Search `Result` `sourceTypes`, `authors`, `providerUrl` from actor-readable metadata.
- [x] OpenAPI + Hey API regenerated.
- [x] Backend tests: SearchTool source fields, history without fields, direct Search metadata, ChatSource validation.

## 2. Original PDF readers

- [x] Repository query, `DocumentOriginalService`, Chat and Search endpoints.
- [x] `DocumentOriginalServiceTest` (authority, recheck after open, magic bytes, size) and `SourceOriginalPdfQueryTest` (readable current PDF only).
- [ ] API integration test for headers and 404 on non-PDF.

## 3. Frontend

- [x] `DocumentSourceIcon`, presentation and provenance helpers with unit tests.
- [x] Chat chip, preview, toolbar stack, panel list/header, Drive link.
- [x] Search card meta line and preview dialog header.
- [x] `EvidenceViewSwitch` + lazy `DocumentPdfView` (react-pdf 11.0.0 / pdfjs-dist 6.3.289).
- [x] Verify pdf.js worker under the production CSP (`nginx.conf`) and Vite build.  Vite build emits the worker as a same-origin asset and `document-pdf-view` as a lazy chunk; a live nginx CSP check (image decoders may need `wasm-unsafe-eval`) remains open. Staging (2026-09-14) showed the page reloading on every PDF tab: nginx served `pdf.worker.min-*.mjs` as `application/octet-stream` with `nosniff`, the module worker failed, pdf.js imported the worker on the main thread through Vite's preload wrapper, and `preload-error-reload` treated that failure as a stale chunk. Fixed with an `.mjs` JavaScript MIME location, a `frontend-image` MIME smoke check, and a reload exemption for pdf.js worker imports.
- [x] Playwright: citation chip/preview/panel with PDF + Drive fixture; PDF page view with outlined box; Search card and dialog; mobile.  Passes with `--workers=1`; the parallel `chat.spec.ts` run hangs on "Đang tải trang" on pristine origin/main too.

## 4. Verification and docs

- [ ] `pnpm check`, `./gradlew clean check`.
- [x] `docs/specs/chat.md`, `docs/specs/search.md`, `docs/specs/document.md` (provenance keys as presentation contract); rows in `docs/tests/chat.md`, `docs/tests/search.md`.
- [x] Add increment to `AGENTS.md` and `docs/roadmap.md`.

## Owner UI review corrections (2026-09-14)

- [x] Fixture PDF boxes were invented and did not fit the glyphs; they now use the extents pdf.js reports for `cited-handbook.pdf` (18 pt Helvetica baselines 680/620).
- [x] Multiply-blend highlighter instead of a translucent fill that greyed the cited text.
- [x] Page counter and fit/150/200/300% zoom; pages on a sunken canvas with a shadow.
- [x] Radix `Tabs` segmented control instead of two `aria-pressed` buttons.
- [x] Provider name is the Drive link; separate "Mở trong Google Drive" links and the stale passage hint on PDF tabs removed.
- [x] Neutral citation chip (no inverted fill), no badge on 14 px icons, hover card title first with the excerpt as a highlighted quote.
- [x] Search facets and file-type menu include every media type on the page (Google Sheets was uncounted).
- [x] `ChatSourceLegacyJsonTest` proves pre-MEM-87 source JSON still reads.
- [ ] Re-captured light, dark and zoom screenshots attached to MEM-87 with the earlier ones as "before".

## Search and navigation extension (owner-approved 2026-09-14)

- [x] `SearchPage.totalResults` (core + `SearchPageResponse`, required), OpenAPI and client regenerated; `DocumentSearchServiceTest` asserts it on both pages.
- [x] Search landing: single-frame box, file-type chips that preselect the filter, per-actor recent searches with Clear all.
- [x] Facet rail removed; shared `TablePagination`; heading uses the total; empty/error states without a card.
- [x] Sidebar `Search documents` entry; `ChatModeMenu` and `AppShell.chatMode` removed.
- [x] Conversation search icon beside the collapse button (collapsed rail keeps an icon row); ChatGPT-style palette with New chat, day groups and time.
- [x] Backend `ChatSessionMatch.snippet` via `ts_headline` (options checked on the staging PostgreSQL with a pure `SELECT`); `ChatSessionApiIntegrationTest` asserts the delimited fragment and null cases.
- [x] Leaving a chat route through a sidebar link (Search documents, and already Assistants/Projects) kept the running conversation's reader open because visibility followed only the main thread; it now also requires a chat route. The `chat.spec.ts` mobile drawer case navigates straight to Search documents and asserts readers drop to 0.
- [x] CI `ModulithArchitectureTest`: `DocumentOriginalService` opens stored objects, so the `retrieval` module now allows `objectstorage` (already allowed for `chat`, `connector` and `document`; `objectstorage` depends only on `iam`, so no cycle). `ARCHITECTURE.md` shows the edge.
- [ ] `ChatSessionApiIntegrationTest` run (needs Docker PostgreSQL; not run locally for RAM).
- [ ] Desktop screenshots in `D:\MemoryOS\output\search-web\`.

## Whole-document PDF by HTTP range (owner-approved 2026-09-14)

- [x] `ObjectStorage.openRange` + S3 implementation; `S3ObjectStorageIntegrationTest` reads a clamped range from MinIO.
- [x] `DocumentOriginalService` per-range authority, metadata inspection, provider range check, `%PDF-` from byte 0, 416 only for readers; `DocumentOriginalServiceTest` (9 cases).
- [x] `DocumentOriginalResponses` 200/206/416 headers and parsing; `DocumentOriginalResponsesTest`; OpenAPI 206/416 and `Range` header, client regenerated.
- [x] `DocumentPdfView` whole document with placeholders, range options, current-page counter and return action; `pdf-page-window.test.ts`.
- [x] Browser: generated 12-page 6 MiB PDF served by range in `search.spec.ts` and `chat.spec.ts`; old `cited-handbook.pdf` removed.
- [ ] Staging measurement: requests, statuses and bytes for one opening of an ~18 MB original from the nginx access log, compared with the whole-file baseline.
