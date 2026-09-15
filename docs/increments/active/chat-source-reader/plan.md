# Plan

## 1. PDF load resilience

- [x] Range-load failure falls back to one whole read and reports to Sentry (`pdf-view`); `search.spec.ts` serves 500 for ranges and still renders the cited page.
- [x] pdf.js decoders emitted to `assets/pdfjs-<version>/` and passed as `wasmUrl`; a local scanned JBIG2 PDF decodes under the production CSP without warnings.
- [ ] Read the Sentry `pdf-view` event for the staging HUT report after deployment and fix the range failure at its cause.

## 2. Source reader

- [x] `SourceLocation.table` from `tableRow`; `source-provenance.test.ts`.
- [x] `EvidenceViewSwitch` opens table rows on "PDF pages" and accepts a controlled view (Chat and Search).
- [x] Panel header `Source n / total` with previous/next, focus kept on the pressed arrow.
- [x] Expand into `DocumentPreviewDialog` with Chat authority, one reader at a time, Escape and focus return.
- [x] `chat.spec.ts` source panel case (desktop and mobile) and `search.spec.ts` table-row preview.
- [ ] Desktop screenshots: stepping header, table citation on its page, expanded dialog.

## 3. Verification and docs

- [x] Full `chat.spec.ts` and `search.spec.ts` with one worker (2026-09-15). The Luna model and grounded citation cases failed once, before any reader code, and passed on rerun. The source panel case expected the table-row source to open on passages; it now asserts the PDF tab. The mobile variant no longer reads the chip behind the modal sheet. Both variants and the Search PDF case pass.
- [x] `typecheck`, `lint`, `check:i18n`, 107 unit tests in `features/search` and `features/chat`.
- [x] `docs/specs/chat.md`, `docs/specs/search.md`, `docs/tests/chat.md`, `docs/tests/search.md`; AGENTS and roadmap entries.
