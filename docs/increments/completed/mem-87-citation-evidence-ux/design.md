# MEM-87 — Citation and evidence UX in Chat and Search

Linear: [MEM-87](https://linear.app/memory-os/issue/MEM-87) — rescoped by the owner on 2026-09-14 to Chat **and** Search, with Google Drive deep links and a PDF page view with cited regions ("best practice, effort is not a constraint").

## Goal

A reader can tell at a glance **what** a cited or matched document is (file type), **where** it comes from (provider), **where inside it** the evidence sits (page, sheet), then verify it — as passages, as the original PDF page with the cited region outlined, or in Google Drive — and return. Chat and Search share one visual language.

## Baseline (origin/main 8e21172)

- Document and file citations, the Sources toolbar stack and Search cards used one generic `FileText` glyph; no provider, no location.
- `ChatSource` had no media or source type. Search `Result` had `mediaType` only; direct Search did not attach Source metadata.
- Chunk provenance already carried Docling `prov` (`page_no`, `bbox` in PDF points, `coord_origin`), spreadsheet `sheetName` and Google Docs `tabId`/`section`, but no UI read it.
- No endpoint served Source originals; `connector_items.provider_file_id` never left Java.

## References

### Glean (documented behaviour, not inspected source)

[Citations](https://docs.glean.com/user-guide/assistant/glean-chat/glean-chat-citations/glean-citations), [deep-linked citations](https://docs.glean.com/user-guide/assistant/glean-chat/glean-chat-citations/deep-linked-citations), [developer guide](https://developers.glean.com/guides/chat/deep-linked-citations), [search result rendering](https://developers.glean.com/api-info/indexing/datasource/rendering-search-results): datasource-icon citation chips, hover preview with the cited text highlighted in context plus page number and Expand, "View sources", search rows with icon, title, snippet and a meta line; citations never grant access.

### Onyx (inspected `D:\MemoryOS\.tmp\onyx` at 40eb240df)

- Citation chip `SourceTag` stacks up to three connector icons (`SourceIcon` → `getSourceMetadata`); file-type icons are not used for citations.
- Citations open `document.link` in a new tab. Google Docs links carry `?tab=` and `#heading=`; other Drive files use `webViewLink` (`connectors/google_drive/section_extraction.py`, `doc_conversion.py`).
- PDFs render in a plain `<iframe>` (`PreviewModal/variants/pdfVariant.tsx`) only for uploaded files without a link. Onyx stores no page numbers or boxes (pages are joined with blank lines) and never jumps to or highlights the cited region.

| Topic | Reference behaviour | MemoryOS | Benefit | Cost |
|---|---|---|---|---|
| Source icon | Onyx: connector icon; Glean: datasource icon | File-type glyph + external provider badge (`DocumentSourceIcon`) | Type and provider visible without hovering | Frontend + additive metadata |
| Citation chip | Glean chip on fragment; Onyx `SourceTag` | Kind icon, number, truncated title; neutral pill with an outlined open/current state (Gemini Notebook numeric pills: citations must not outweigh the prose) | Identify evidence inline | Frontend |
| Location | Glean page number; Onyx none | Page range / sheet name from existing provenance | Verify position before opening | Frontend only |
| Provider link | Onyx per-connector links; Glean click-through | Drive universal open URL built server-side | Open the source of record | Additive DB column read |
| PDF evidence | Glean highlighted text preview; Onyx iframe, no location | Cited pages rendered with pdf.js on a sunken canvas, Docling boxes highlighted with a multiply-blend highlighter, page counter and zoom (Sana AI / Dovetail PDF headers); passages stay default | Visual verification of tables/figures | New authorized original endpoints, `react-pdf` dependency |
| Access | Both: no new access | Readers recheck authority before and after opening storage | — | — |

Mobbin references reviewed with the owner: Dropbox Dash citation chip and search (type icon + app badge), Confluence/Rovo meta line, Perplexity multi-source popover, Elicit highlighted evidence, ChatGPT and Langdock file-type icons.

## Design

### Presentation metadata (backend)

- `ChatSource` adds `mediaType`, `sourceTypes` (distinct, answer-time) and `providerUrl`; all nullable/empty for existing JSONB rows, no migration, still inside the 24-entry / 128 KiB bound. Web sources carry none. File citations record the file descriptor's media type.
- `DocumentSourceMetadata` adds `providerFileId` from `connector_items.provider_file_id`. It is **not** written to the index `source_metadata` (that map feeds `metadata_hash`; adding a key would rewrite every chunk).
- `providerUrl` = `https://drive.google.com/open?id=<id>` for the first readable Google Drive origin whose id matches `[A-Za-z0-9_-]{10,256}`. One URL for native Docs/Sheets/Slides and binary files; recorded media type never picks an editor path (Slides are stored as exported PPTX). Heading anchors need `headingId`, which provenance does not keep — candidate only. The browser accepts only that exact URL shape.
- Direct Search attaches `sourceTypes`, up to five `authors` and `providerUrl` for the returned page only, through `SourceSearchService.readableMetadata` (actor-readable mappings; one batch query per page). Raw index metadata is not used because it lists mappings the actor cannot read.

### Original PDF readers

- `GET /api/chat/documents/{id}/original?generation=` (membership + Document eligibility, like the Chat passage reader) and `GET /api/search/documents/{id}/original?generation=` (`SEARCH_READ`).
- `DocumentOriginalService`: current generation + `canRead`; `JdbcSourceDocumentRepository.originalPdf` selects the stored object of an actor-readable, active mapping whose current item version's SHA-256 equals the Document's `source_content_sha256` and whose Document media type is `application/pdf`; size ≤ 64 MiB. After `storage.open` it verifies object metadata, `%PDF-` magic bytes, authority, eligibility and the same object again, closing the stream on any failure.
- Response: `application/octet-stream`, attachment disposition, `Cache-Control: no-store`, `nosniff` — the Chat file download contract.
- Native Google Docs/Sheets (JSON snapshots) and Office files have no original view; they use passages and the Drive link.

### Shared UI

- `DocumentSourceIcon` (`features/search`): lucide glyph per kind with semantic status tints; Google Drive badge from `source-provider-catalog`; uploads get no badge; chip-size (`xs`) icons omit the badge because at 14 px it is an unreadable smudge and adjacent text names the provider; missing metadata renders the neutral glyph. `document-source-presentation.ts` holds kind/label mapping (Google Docs/Sheets/Slides labels added to `friendlyMediaType`).
- `source-provenance.ts`: tolerant reader for Docling pages/boxes, table-row `source` wrappers and sheet names; `formatPages`; `pdfBoxRect` maps BOTTOMLEFT/TOPLEFT PDF points onto the rendered page and skips rotated pages (orientation-corrected frames are not assumed to match).
- `ProviderLink` / `DocumentMeta` (`provider-link.tsx`): the provider name is the Drive link (`Google Drive ↗`), so a source never shows the provider next to a separate "Mở trong Google Drive". Places that are themselves buttons (panel list cards) keep the meta as plain text.
- `EvidenceViewSwitch`: Radix `Tabs` segmented list "Đoạn trích | Trang PDF" (tablist semantics, arrow keys) shown only for PDF evidence with recorded pages; inactive panels unmount, so pdf.js loads only on the PDF tab. `DocumentPdfView` is lazy-loaded (pdf.js stays out of the main bundle and unit tests), fetches bytes once per opening (`staleTime: Infinity`, `gcTime: 5_000`: the view remounts while the dialog settles, and a zero cache time re-downloaded the PDF several times), renders at most five cited pages without text/annotation layers on a sunken canvas with a `Trang x / n` counter and fit/150/200/300% zoom (a Letter page at Chat panel width is about 0.6×, too small for body text), highlights boxes and scrolls to the first. Boxes use `mix-blend-multiply` over the always-white page so cited text stays black (a translucent fill greyed it); that highlighter token is theme-independent, while passage highlights use per-theme `evidence-highlight` tokens. The worker is bundled from `pdfjs-dist` (`script-src 'self'`).

### Chat

- Inline citation chip: kind icon, number, truncated title; neutral pill, `aria-current` with an outlined (not inverted) state while its evidence is open. Web chips show the number and hostname (AI Elements' hostname badge) with the favicon when it loads and no letter fallback, which read as part of the number ("R2"); the hover card keeps the page title and adds the full URL. The number stays because it links the chip to the Sources panel list, and each `[n]` stays its own chip instead of AI Elements' `+N` carousel. Icon stacks fall back to a globe.
- Hover preview: icon, two-line title, then `Nguồn n · Type · Google Drive↗ · Trang x`, excerpt as a highlighted quote.
- Sources toolbar stack: distinct Web hosts or document kind, as the accepted Sources control specifies. Keying documents by provider as well produced identical PDF glyphs side by side once chip-size icons dropped the badge. The label stays the generic "Nguồn" with the count in the accessible name (owner-approved in chat-edit-navigation-polish, replacing the earlier count pill).
- Panel list: flat, fully clickable source rows (Onyx `ChatDocumentDisplay`; WRITER and Gemini source panels on Mobbin) replace assistant-ui `DocumentReference`, whose multi-anchor card nested a bordered anchor with a repeated "Nguồn n" label, a second quote bar and monospace meta around every single citation. Row: 32 px tile, two-line title with the citation number, plain type/site meta, two-line excerpt; hover and focus tint only. `ChatSourceHeader` gives documents and Web pages the same identity header; the Web reader shows its saved excerpt with the evidence highlight. Evidence header: icon, title, linked meta; the passage hint only when there are no PDF tabs (it is false on the page tab), then the tabs. Consecutive cited passages form one continuous highlight.

### Search

- Result card (reworked after owner review, following Confluence, Databricks and Slite result lists on Mobbin): icon, title, one plain meta line `Type · Google Drive↗ · Authors · Updated` whose separators hide at wrapped line starts, then the best match itself as the clickable context and related matches as compact `↳` lines. The earlier nested "Best match / View context ↗" block repeated a label per match, misaligned the snippet with the title and reused ↗ for an in-app dialog. Every match keeps its own control and accessible name; query terms use the evidence highlight. Results header is one line `n results for "query"`; the file-type facet drops its card, lists present types first and mutes empty ones, which stay selectable (MEM-46). Icon tiles use `surface-subtle` so dark mode shows no black squares.
- Preview dialog: icon, title, linked type/provider description and the same PDF tabs using the opened match's provenance. Its footer is one row: a segmented match switcher (same style as the evidence tabs, `aria-current` on the open match) and compact part paging; the earlier two stacked footers with a filled primary match button took up to ~150 px on mobile. The file-type menu uses the facet's friendly names.
- File-type facets and the file-type menu add every media type present on the page (and the selected one) to the fixed filters; Google Sheets results were previously uncounted. The API already accepts any valid media type filter.

### Owner-approved Search and navigation extension (2026-09-14)

After reviewing the Search screenshots the owner approved a wider change than citation evidence. It departs from two accepted decisions, recorded in the specs in the same change: the header Chat/Search mode selector (`docs/specs/chat.md`) and the conversation search trigger beside Recent conversations (chat-history-search increment).

| Topic | Reference | MemoryOS | Why |
|---|---|---|---|
| Search entry | Onyx and Glean keep Search as a primary navigation destination | Sidebar `Search documents` entry; header mode menu removed | Search is a primary capability; a hidden menu under the page title was hard to find |
| Landing | Dropbox Dash, Confluence and Perplexity on Mobbin: one query box, recent queries, quick scopes | Single-frame box (the nested sunken field is gone); file-type chips preselect the filter; five recent queries per actor in `localStorage` with Clear all | Chips cannot search on their own because `SearchRequest` needs a query |
| Facet rail | Confluence and Slite rely on filter menus | Rail removed; the menu still lists every media type on the page; results column capped at `max-w-4xl` | The rail counted only the current page (10 results) and repeated the menu |
| Paging | Shared admin tables | `TablePagination` with `Showing a–b of N` and `page / pages`; backend adds `totalResults` (readable grouped Documents among candidates, `500+` at the bound) | Page counts and the heading showed only the page size |
| Empty/error | — | No card; spans the results column | The boxed state looked like a result card |
| Conversation search | ChatGPT search dialog | Icon beside the collapse button, Ctrl/Cmd+K; palette with New chat, day groups, title, `ts_headline` fragment and time | Owner request; the fragment explains why a title-only hit matched |

The snippet is computed only for the returned page with `ts_headline` on the newest indexed matching message; delimiters are U+E000/U+E001 rendered as text, so no HTML leaves the server. This supersedes the MEM-46 facet line and the facet notes in the Search section above.

## Out of scope / candidates

Docs heading deep links (`headingId` not extracted), PDF text-span highlighting inside a box, people citations, author display in Chat, ranking, ACL changes (MEM-93/MEM-88), Sources toolbar layout (accepted decision kept).
